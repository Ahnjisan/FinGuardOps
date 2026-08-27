package com.aifds.backend.persistence;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyServiceIntegrationTest extends PostgresqlIntegrationTestSupport {

    private static final String OPERATION_SCOPE = "POST:/api/v1/transactions";

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private FinancialTransactionRepository financialTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DatabaseTransactionTimestampProvider timestampProvider;

    @Test
    void acquiresNewRequestAsCommittedInProgressRecord() {
        String key = key("new-claim");

        IdempotencyClaimResult result = idempotencyService.claim(key, fingerprintInput());

        assertThat(result).isInstanceOf(IdempotencyClaimResult.Acquired.class);
        long recordId = ((IdempotencyClaimResult.Acquired) result).recordId();
        IdempotencyRecord stored = idempotencyRecordRepository.findById(recordId).orElseThrow();
        assertThat(stored.getOperationScope()).isEqualTo(OPERATION_SCOPE);
        assertThat(stored.getIdempotencyKey()).isEqualTo(key);
        assertThat(stored.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
    }

    @Test
    void identifiesSameKeyAndFingerprintAsInProgress() {
        String key = key("same-request");
        TransactionFingerprintInput input = fingerprintInput();
        idempotencyService.claim(key, input);

        assertThat(idempotencyService.claim(key, input))
                .isInstanceOf(IdempotencyClaimResult.InProgress.class);
    }

    @Test
    void identifiesSameKeyAndDifferentFingerprintAsConflict() {
        String key = key("different-request");
        idempotencyService.claim(key, fingerprintInput());

        assertThat(idempotencyService.claim(key, fingerprintInput(UUID.randomUUID())))
                .isInstanceOf(IdempotencyClaimResult.KeyConflict.class);
    }

    @Test
    void persistsCompletedTransitionAndReusesStoredSnapshot() {
        String key = key("completed");
        TransactionFingerprintInput input = fingerprintInput();
        FinancialTransaction transaction = financialTransactionRepository.saveAndFlush(
                transaction(input.transactionId())
        );
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        ObjectNode snapshot = objectMapper.createObjectNode()
                .put("transactionId", transaction.getTransactionId().toString())
                .put("processingStatus", "RECEIVED");
        Instant finishedAt = finishedAtFor(recordId);

        IdempotencyClaimResult.Completed completed = idempotencyService.complete(
                recordId,
                transaction.getTransactionId(),
                snapshot,
                finishedAt
        );
        IdempotencyRecord stored = idempotencyRecordRepository.findById(recordId).orElseThrow();
        IdempotencyClaimResult replay = idempotencyService.claim(key, input);

        assertThat(completed.responseSnapshotJson()).contains(transaction.getTransactionId().toString());
        assertThat(stored.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        assertThat(stored.getFinancialTransaction().getId()).isEqualTo(transaction.getId());
        assertThat(stored.getResponseSnapshot()).isEqualTo(snapshot);
        assertThat(stored.getFailureCode()).isNull();
        assertThat(stored.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(replay).isEqualTo(completed);
    }

    @Test
    void completesWhenSameTransactionWasAlreadyLinked() {
        String key = key("linked-completed");
        TransactionFingerprintInput input = fingerprintInput();
        FinancialTransaction transaction = financialTransactionRepository.saveAndFlush(
                transaction(input.transactionId())
        );
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        IdempotencyRecord record =
                idempotencyRecordRepository.findById(recordId).orElseThrow();
        record.linkTransaction(transaction);
        idempotencyRecordRepository.saveAndFlush(record);
        Instant finishedAt = finishedAtFor(recordId);

        IdempotencyClaimResult.Completed completed = idempotencyService.complete(
                recordId,
                transaction.getTransactionId(),
                objectMapper.createObjectNode().put("result", "completed"),
                finishedAt
        );

        assertThat(completed.responseSnapshotJson()).contains("completed");
        IdempotencyRecord stored =
                idempotencyRecordRepository.findById(recordId).orElseThrow();
        assertThat(stored.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        assertThat(stored.getFinancialTransaction().getId())
                .isEqualTo(transaction.getId());
        assertThat(stored.getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void rejectsCompletionWithDifferentTransactionWithoutChangingRecord() {
        String key = key("linked-mismatch");
        TransactionFingerprintInput input = fingerprintInput();
        FinancialTransaction linked = financialTransactionRepository.saveAndFlush(
                transaction(input.transactionId())
        );
        FinancialTransaction different = financialTransactionRepository.saveAndFlush(
                transaction(UUID.randomUUID())
        );
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        IdempotencyRecord record =
                idempotencyRecordRepository.findById(recordId).orElseThrow();
        record.linkTransaction(linked);
        idempotencyRecordRepository.saveAndFlush(record);
        Instant finishedAt = finishedAtFor(recordId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> idempotencyService.complete(
                        recordId,
                        different.getTransactionId(),
                        objectMapper.createObjectNode().put("result", "rejected"),
                        finishedAt
                )
        ).isInstanceOf(IllegalStateException.class);

        IdempotencyRecord stored =
                idempotencyRecordRepository.findById(recordId).orElseThrow();
        assertThat(stored.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(stored.getFinancialTransaction().getId())
                .isEqualTo(linked.getId());
        assertThat(stored.getResponseSnapshot()).isNull();
        assertThat(stored.getFailureCode()).isNull();
        assertThat(stored.getFinishedAt()).isNull();
    }

    @Test
    void persistsFailedTransitionAndReturnsExistingFailure() {
        String key = key("failed");
        TransactionFingerprintInput input = fingerprintInput();
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        Instant failedAt = finishedAtFor(recordId);
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(failedAt);

        IdempotencyClaimResult.Failed failed = idempotencyService.fail(
                recordId,
                "DEPENDENCY_TIMEOUT"
        );
        IdempotencyRecord stored = idempotencyRecordRepository.findById(recordId).orElseThrow();
        IdempotencyClaimResult replay = idempotencyService.claim(key, input);

        assertThat(failed.failureCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(stored.getProcessingStatus()).isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(stored.getFinancialTransaction()).isNull();
        assertThat(stored.getResponseSnapshot()).isNull();
        assertThat(stored.getFailureCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(stored.getFinishedAt()).isEqualTo(failedAt);
        assertThat(replay).isEqualTo(failed);
    }

    @Test
    void persistsTypedFailureAndReturnsStoredSnapshotSeparatelyFromLegacy()
            throws Exception {
        String key = key("typed-failed");
        TransactionFingerprintInput input = fingerprintInput();
        FinancialTransaction transaction = financialTransactionRepository.saveAndFlush(
                transaction(input.transactionId())
        );
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        IdempotencyRecord record = idempotencyRecordRepository
                .findById(recordId)
                .orElseThrow();
        record.linkTransaction(transaction);
        idempotencyRecordRepository.saveAndFlush(record);
        Instant failedAt = finishedAtFor(recordId);
        when(timestampProvider.currentTransactionTimestamp()).thenReturn(failedAt);

        IdempotencyClaimResult.FailedWithSnapshot failed =
                idempotencyService.failWithSnapshot(
                        recordId,
                        "DEPENDENCY_TIMEOUT",
                        this::timeoutFailureSnapshot
                );
        IdempotencyRecord stored = idempotencyRecordRepository
                .findById(recordId)
                .orElseThrow();
        IdempotencyClaimResult replay = idempotencyService.claim(key, input);

        assertThat(failed.finishedAt()).isEqualTo(failedAt);
        assertThat(failed.responseSnapshotJson())
                .contains("external-risk-failure", failedAt.toString());
        assertThat(stored.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(stored.getFinancialTransaction().getId())
                .isEqualTo(transaction.getId());
        assertThat(stored.getResponseSnapshot())
                .isEqualTo(timeoutFailureSnapshot(failedAt));
        assertThat(stored.getFailureCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(stored.getFinishedAt()).isEqualTo(failedAt);
        assertThat(replay)
                .isInstanceOf(IdempotencyClaimResult.FailedWithSnapshot.class);
        IdempotencyClaimResult.FailedWithSnapshot replayed =
                (IdempotencyClaimResult.FailedWithSnapshot) replay;
        assertThat(replayed.failureCode()).isEqualTo(failed.failureCode());
        assertThat(replayed.finishedAt()).isEqualTo(failed.finishedAt());
        assertThat(objectMapper.readTree(replayed.responseSnapshotJson()))
                .isEqualTo(objectMapper.readTree(failed.responseSnapshotJson()));
    }

    @Test
    void treatsExpiredRecordAsExistingWithoutDeletionOrReacquisition() {
        String key = key("expired");
        TransactionFingerprintInput input = fingerprintInput();
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        jdbcTemplate.update("""
                UPDATE idempotency_record
                SET created_at = created_at - INTERVAL '25 hours',
                    expires_at = expires_at - INTERVAL '25 hours'
                WHERE id = ?
                """, recordId);

        IdempotencyClaimResult replay = idempotencyService.claim(key, input);

        assertThat(replay).isInstanceOf(IdempotencyClaimResult.InProgress.class);
        assertThat(countByKey(key)).isEqualTo(1);
        assertThat(idempotencyRecordRepository.findById(recordId)).isPresent();
    }

    @Test
    void concurrentSameRequestAllowsExactlyOneAcquisitionAndOneStoredRecord() throws Exception {
        int requestCount = 6;
        String key = key("concurrent-same");
        TransactionFingerprintInput input = fingerprintInput();
        List<IdempotencyClaimResult> results = runConcurrently(
                requestCount,
                () -> idempotencyService.claim(key, input)
        );

        assertThat(results.stream()
                .filter(IdempotencyClaimResult.Acquired.class::isInstance)
                .count()).isEqualTo(1);
        assertThat(results.stream()
                .filter(IdempotencyClaimResult.InProgress.class::isInstance)
                .count()).isEqualTo(requestCount - 1L);
        assertThat(countByKey(key)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentFingerprintsAllowOneAcquisitionAndOneConflict() throws Exception {
        String key = key("concurrent-different");
        TransactionFingerprintInput first = fingerprintInput();
        TransactionFingerprintInput second = fingerprintInput(UUID.randomUUID());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<IdempotencyClaimResult> firstFuture = executor.submit(
                    barrierTask(barrier, () -> idempotencyService.claim(key, first))
            );
            Future<IdempotencyClaimResult> secondFuture = executor.submit(
                    barrierTask(barrier, () -> idempotencyService.claim(key, second))
            );
            List<IdempotencyClaimResult> results = List.of(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS)
            );

            assertThat(results.stream()
                    .filter(IdempotencyClaimResult.Acquired.class::isInstance)
                    .count()).isEqualTo(1);
            assertThat(results.stream()
                    .filter(IdempotencyClaimResult.KeyConflict.class::isInstance)
                    .count()).isEqualTo(1);
            assertThat(countByKey(key)).isEqualTo(1);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentCompleteAndTypedFailAllowOneTerminalTransitionAndRejectTheOther() throws Exception {
        String key = key("terminal-race");
        TransactionFingerprintInput input = fingerprintInput();
        FinancialTransaction transaction = financialTransactionRepository.saveAndFlush(
                transaction(input.transactionId())
        );
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        IdempotencyRecord linkedRecord = idempotencyRecordRepository
                .findById(recordId)
                .orElseThrow();
        linkedRecord.linkTransaction(transaction);
        idempotencyRecordRepository.saveAndFlush(linkedRecord);
        Instant finishedAt = finishedAtFor(recordId);
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(finishedAt);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TransitionAttempt> completeFuture = executor.submit(
                    transitionTask(barrier, () -> idempotencyService.complete(
                            recordId,
                            transaction.getTransactionId(),
                            objectMapper.createObjectNode().put("result", "completed"),
                            finishedAt
                    ))
            );
            Future<TransitionAttempt> failFuture = executor.submit(
                    transitionTask(barrier, () -> idempotencyService.failWithSnapshot(
                            recordId,
                            "DEPENDENCY_TIMEOUT",
                            this::timeoutFailureSnapshot
                    ))
            );
            List<TransitionAttempt> attempts = List.of(
                    completeFuture.get(30, TimeUnit.SECONDS),
                    failFuture.get(30, TimeUnit.SECONDS)
            );

            assertThat(attempts.stream().filter(TransitionAttempt::succeeded).count())
                    .isEqualTo(1);
            assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).count())
                    .isEqualTo(1);
            assertThat(attempts.stream()
                    .filter(attempt -> !attempt.succeeded())
                    .map(TransitionAttempt::failure))
                    .allSatisfy(failure -> assertThat(failure)
                            .isInstanceOf(IdempotencyStateTransitionNotAllowedException.class));

            IdempotencyRecord stored = idempotencyRecordRepository.findById(recordId).orElseThrow();
            assertThat(stored.getProcessingStatus()).isIn(
                    IdempotencyProcessingStatus.COMPLETED,
                    IdempotencyProcessingStatus.FAILED
            );
            assertThat(stored.getFinishedAt()).isEqualTo(finishedAt);
            assertThat(countByKey(key)).isEqualTo(1);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentTypedFailureWritersAllowExactlyOneTransition() throws Exception {
        String key = key("typed-terminal-race");
        TransactionFingerprintInput input = fingerprintInput();
        FinancialTransaction transaction = financialTransactionRepository.saveAndFlush(
                transaction(input.transactionId())
        );
        long recordId = acquiredRecordId(idempotencyService.claim(key, input));
        IdempotencyRecord record = idempotencyRecordRepository
                .findById(recordId)
                .orElseThrow();
        record.linkTransaction(transaction);
        idempotencyRecordRepository.saveAndFlush(record);
        Instant finishedAt = finishedAtFor(recordId);
        when(timestampProvider.currentTransactionTimestamp()).thenReturn(finishedAt);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<TransitionAttempt>> futures = List.of(
                    executor.submit(transitionTask(
                            barrier,
                            () -> idempotencyService.failWithSnapshot(
                                    recordId,
                                    "DEPENDENCY_TIMEOUT",
                                    this::timeoutFailureSnapshot
                            )
                    )),
                    executor.submit(transitionTask(
                            barrier,
                            () -> idempotencyService.failWithSnapshot(
                                    recordId,
                                    "DEPENDENCY_TIMEOUT",
                                    this::timeoutFailureSnapshot
                            )
                    ))
            );
            List<TransitionAttempt> attempts = List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS)
            );

            assertThat(attempts.stream().filter(TransitionAttempt::succeeded).count())
                    .isEqualTo(1);
            assertThat(attempts.stream()
                    .filter(attempt -> !attempt.succeeded())
                    .map(TransitionAttempt::failure))
                    .singleElement()
                    .isInstanceOf(IdempotencyStateTransitionNotAllowedException.class);
            IdempotencyRecord stored = idempotencyRecordRepository
                    .findById(recordId)
                    .orElseThrow();
            assertThat(stored.getProcessingStatus())
                    .isEqualTo(IdempotencyProcessingStatus.FAILED);
            assertThat(stored.getResponseSnapshot())
                    .isEqualTo(timeoutFailureSnapshot(finishedAt));
        } finally {
            shutdown(executor);
        }
    }

    private List<IdempotencyClaimResult> runConcurrently(
            int taskCount,
            Callable<IdempotencyClaimResult> action
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(taskCount);
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        List<Future<IdempotencyClaimResult>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < taskCount; index++) {
                futures.add(executor.submit(barrierTask(barrier, action)));
            }
            List<IdempotencyClaimResult> results = new ArrayList<>();
            for (Future<IdempotencyClaimResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            shutdown(executor);
        }
    }

    private <T> Callable<T> barrierTask(CyclicBarrier barrier, Callable<T> action) {
        return () -> {
            barrier.await(30, TimeUnit.SECONDS);
            return action.call();
        };
    }

    private Callable<TransitionAttempt> transitionTask(
            CyclicBarrier barrier,
            Callable<?> action
    ) {
        return () -> {
            barrier.await(30, TimeUnit.SECONDS);
            try {
                action.call();
                return TransitionAttempt.success();
            } catch (Throwable failure) {
                return TransitionAttempt.failure(failure);
            }
        };
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private long acquiredRecordId(IdempotencyClaimResult result) {
        assertThat(result).isInstanceOf(IdempotencyClaimResult.Acquired.class);
        return ((IdempotencyClaimResult.Acquired) result).recordId();
    }

    private int countByKey(String key) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_record
                WHERE operation_scope = ? AND idempotency_key = ?
                """, Integer.class, OPERATION_SCOPE, key);
    }

    private Instant finishedAtFor(long recordId) {
        return idempotencyRecordRepository.findById(recordId)
                .orElseThrow()
                .getCreatedAt()
                .plus(1, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private TransactionFingerprintInput fingerprintInput() {
        return fingerprintInput(UUID.randomUUID());
    }

    private TransactionFingerprintInput fingerprintInput(UUID transactionId) {
        return new TransactionFingerprintInput(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES),
                "cust_ref_service_integration",
                "acct_ref_service_integration_sender",
                "acct_ref_service_integration_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_service_integration"
        );
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES),
                "cust_ref_service_integration",
                "acct_ref_service_integration_sender",
                "acct_ref_service_integration_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_service_integration"
        );
    }

    private ObjectNode timeoutFailureSnapshot(Instant finalizedAt) {
        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.put("code", "DEPENDENCY_TIMEOUT");
        responseBody.put("message", "탐지 서비스를 사용할 수 없습니다.");
        responseBody.putArray("fieldErrors");
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("snapshotType", "external-risk-failure");
        snapshot.set("responseBody", responseBody);
        snapshot.put("httpStatus", 503);
        snapshot.put("failureCategory", "TIMEOUT");
        snapshot.put("responseSchemaVersion", "transaction-create-error-v1");
        snapshot.put(
                "codecVersion",
                "external-risk-failure-snapshot-envelope-v1"
        );
        snapshot.put("finalizedAt", finalizedAt.toString());
        return snapshot;
    }

    private record TransitionAttempt(boolean succeeded, Throwable failure) {

        private static TransitionAttempt success() {
            return new TransitionAttempt(true, null);
        }

        private static TransitionAttempt failure(Throwable failure) {
            return new TransitionAttempt(false, failure);
        }
    }
}
