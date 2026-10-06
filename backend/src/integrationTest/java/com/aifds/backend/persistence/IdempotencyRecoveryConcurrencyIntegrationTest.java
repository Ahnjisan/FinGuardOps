package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.aifds.backend.idempotency.service.IdempotencyMaintenanceSafetyVerifier;
import com.aifds.backend.idempotency.service.TransactionIntakeMaintenanceGate;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionSynchronousProcessingCoordinator;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandArguments;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandResult;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyRecoveryConcurrencyIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-28T00:00:00Z");

    @Autowired
    private IdempotencyRecoveryService recoveryService;
    @MockitoSpyBean
    private RiskResponseFinalizationService finalizationService;
    @Autowired
    private TransactionIntakeService transactionIntakeService;
    @Autowired
    private TransactionRequestFingerprint requestFingerprint;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionSynchronousProcessingCoordinator coordinator;

    @MockitoBean
    private IdempotencyMaintenanceSafetyVerifier safetyVerifier;

    @MockitoBean
    private TransactionIntakeMaintenanceGate maintenanceGate;

    @BeforeEach
    void allowIsolatedRecoveryFixtures() {
        when(safetyVerifier.isSafeForReconciliation()).thenReturn(true);
        when(maintenanceGate.isOpen()).thenReturn(true);
    }

    @Test
    void heldRecordLockProducesTypedRejectionWithoutBusinessMutation()
            throws Exception {
        RecoveryFixture fixture = finalizedFixture(RiskLevel.MEDIUM);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                            ignored -> {
                                jdbcTemplate.queryForObject("""
                                        SELECT id FROM idempotency_record
                                        WHERE id = ? FOR NO KEY UPDATE
                                        """, Long.class, fixture.recordId());
                                locked.countDown();
                                try {
                                    assertThat(release.await(10, TimeUnit.SECONDS))
                                            .isTrue();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(exception);
                                }
                            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(recoveryService.recover(fixture.recordId(),
                    AuditActorType.SYSTEM, AuditLog.SYSTEM_ACTOR_ID)
                    .decision()).isEqualTo(
                    IdempotencyRecoveryDecision.LOCK_CONTENTION);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT processing_status FROM idempotency_record WHERE id = ?
                    """, String.class, fixture.recordId())).isEqualTo("IN_PROGRESS");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT recovery_decision || ':' || audit_result
                    FROM idempotency_recovery_audit_log
                    WHERE idempotency_record_id = ?
                    """, String.class, fixture.recordId()))
                    .isEqualTo("LOCK_CONTENTION:REJECTED");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void heldTransactionLockProducesTypedRejectionWithoutBusinessMutation()
            throws Exception {
        RecoveryFixture fixture = finalizedFixture(RiskLevel.CRITICAL);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                            ignored -> {
                                jdbcTemplate.queryForObject("""
                                        SELECT id FROM financial_transaction
                                        WHERE transaction_id = ? FOR NO KEY UPDATE
                                        """, Long.class, fixture.transactionId());
                                locked.countDown();
                                try {
                                    assertThat(release.await(10, TimeUnit.SECONDS))
                                            .isTrue();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(exception);
                                }
                            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // The record lock remains available; the later transaction lock
            // must be the source of the typed NOWAIT rejection.
            Long lockedRecordId = new TransactionTemplate(transactionManager)
                    .execute(ignored -> idempotencyRecordRepository
                            .findByIdForUpdateNowait(fixture.recordId())
                            .orElseThrow().getId());
            assertThat(lockedRecordId).isEqualTo(fixture.recordId());
            StoredState before = storedState(fixture);
            assertThat(recoveryAudits(fixture.recordId())).isEmpty();

            IdempotencyRecoveryCommandArguments arguments =
                    IdempotencyRecoveryCommandArguments.parse(new String[]{
                            recoveryOption("enabled=true"),
                            recoveryOption("action=recover"),
                            recoveryOption("record-id=" + fixture.recordId()),
                            recoveryOption("instances-terminated-confirmed=true")
                    });
            Future<IdempotencyRecoveryCommandResult> recovery = executor.submit(
                    () -> new IdempotencyRecoveryCommandRunner(
                            recoveryService, objectMapper).run(arguments));
            IdempotencyRecoveryCommandResult rejected =
                    recovery.get(3, TimeUnit.SECONDS);
            assertThat(rejected.exitCode()).isEqualTo(3);
            assertThat(rejected.standardOutputLines()).hasSize(1);
            JsonNode output = objectMapper.readTree(
                    rejected.standardOutputLines().get(0));
            assertThat(output.path("decision").asText())
                    .isEqualTo("LOCK_CONTENTION");
            assertThat(output.path("auditResult").asText())
                    .isEqualTo("REJECTED");
            assertThat(storedState(fixture)).isEqualTo(before);
            assertThat(recoveryAudits(fixture.recordId()))
                    .containsExactly("LOCK_CONTENTION:REJECTED");

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentRecoveryHasOneWinnerAndOneTerminalRejection()
            throws Exception {
        RecoveryFixture fixture = finalizedFixture(RiskLevel.CRITICAL);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Callable<IdempotencyRecoveryResult> invocation = () -> {
                start.await(10, TimeUnit.SECONDS);
                return recoveryService.recover(
                        fixture.recordId(),
                        AuditActorType.SYSTEM,
                        AuditLog.SYSTEM_ACTOR_ID
                );
            };
            Future<IdempotencyRecoveryResult> first = executor.submit(
                    invocation
            );
            Future<IdempotencyRecoveryResult> second = executor.submit(
                    invocation
            );

            List<IdempotencyRecoveryDecision> decisions = List.of(
                    first.get(20, TimeUnit.SECONDS).decision(),
                    second.get(20, TimeUnit.SECONDS).decision()
            );
            assertThat(decisions).contains(
                    IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP);
            assertThat(decisions).anyMatch(decision -> decision
                    == IdempotencyRecoveryDecision.LOCK_CONTENTION
                    || decision == IdempotencyRecoveryDecision.ALREADY_TERMINAL);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        )).isEqualTo("COMPLETED");
        List<String> audits = jdbcTemplate.queryForList(
                """
                        SELECT recovery_decision || ':' || audit_result
                        FROM idempotency_recovery_audit_log
                        WHERE idempotency_record_id = ?
                        ORDER BY id
                        """,
                String.class,
                fixture.recordId()
        );
        assertThat(audits).contains("RECOVERABLE_COMPLETION_GAP:RECOVERED");
        assertThat(audits).anyMatch(audit -> audit.equals(
                "LOCK_CONTENTION:REJECTED") || audit.equals(
                "ALREADY_TERMINAL:REJECTED"));
    }

    @Test
    void publicReplayRacingRecoveryNeverAcquiresNewProcessing()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionFingerprintInput input = fingerprintInput(transactionId);
        String key = "recovery-race-" + UUID.randomUUID();
        RecoveryFixture fixture = finalizedFixture(
                RiskLevel.HIGH,
                input,
                key,
                requestFingerprint.calculate(input)
        );
        when(coordinator.isAvailable()).thenReturn(true);
        clearInvocations(finalizationService, coordinator);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<IdempotencyRecoveryResult> recovery = executor.submit(
                    () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return recoveryService.recover(
                                fixture.recordId(),
                                AuditActorType.SYSTEM,
                                AuditLog.SYSTEM_ACTOR_ID
                        );
                    }
            );
            Future<TransactionIntakeResult> replay = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return transactionIntakeService.receive(
                        key,
                        request(input),
                        "trace_recovery_public_race_01"
                );
            });

            assertThat(recovery.get(20, TimeUnit.SECONDS).decision())
                    .isEqualTo(
                            IdempotencyRecoveryDecision
                                    .RECOVERABLE_COMPLETION_GAP
                    );
            TransactionIntakeResult publicResult = replay.get(
                    20,
                    TimeUnit.SECONDS
            );
            assertThat(publicResult)
                    .isInstanceOfAny(
                            TransactionIntakeResult.InProgress.class,
                            TransactionIntakeResult.CompletedReplay.class
                    )
                    .isNotInstanceOf(TransactionIntakeResult.Received.class);
            if (publicResult
                    instanceof TransactionIntakeResult.CompletedReplay replayed) {
                assertThat(replayed.httpStatus()).isEqualTo(201);
                assertThat(replayed.snapshot().transactionId())
                        .isEqualTo(transactionId);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        )).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM idempotency_recovery_audit_log
                        WHERE idempotency_record_id = ?
                          AND recovery_decision =
                              'RECOVERABLE_COMPLETION_GAP'
                          AND audit_result = 'RECOVERED'
                        """,
                Integer.class,
                fixture.recordId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transaction",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detection_result",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM case_transaction",
                Integer.class
        )).isEqualTo(1);
        verify(coordinator).isAvailable();
        verify(coordinator, never()).process(
                anyLong(),
                any(),
                anyString()
        );
        verify(finalizationService, never()).finalizeRiskResponse(any());
    }

    private RecoveryFixture finalizedFixture(RiskLevel riskLevel) {
        UUID transactionId = UUID.randomUUID();
        TransactionFingerprintInput input = fingerprintInput(transactionId);
        return finalizedFixture(
                riskLevel,
                input,
                "recovery-concurrent-" + UUID.randomUUID(),
                "e".repeat(64)
        );
    }

    private RecoveryFixture finalizedFixture(
            RiskLevel riskLevel,
            TransactionFingerprintInput input,
            String key,
            String fingerprint
    ) {
        long transactionPk = jdbcTemplate.queryForObject(
                """
                        INSERT INTO financial_transaction (
                            transaction_id, transaction_type, amount,
                            currency_code, occurred_at,
                            external_customer_ref, sender_account_ref,
                            recipient_account_ref, channel, device_ref,
                            processing_status
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                        RETURNING id
                        """,
                Long.class,
                input.transactionId(),
                input.transactionType().name(),
                input.amount(),
                input.currencyCode(),
                Timestamp.from(input.occurredAt()),
                input.externalCustomerRef(),
                input.senderAccountRef(),
                input.recipientAccountRef(),
                input.channel().name(),
                input.deviceRef()
        );
        long detectionPk = jdbcTemplate.queryForObject(
                """
                        INSERT INTO detection_result (
                            detection_result_id, financial_transaction_id,
                            detection_result_version, analysis_status,
                            risk_score, risk_level, rule_set_version,
                            scoring_policy_version, feature_version,
                            evaluation_cutoff_at, analysis_started_at,
                            analysis_completed_at, analysis_trace_id
                        ) VALUES (
                            ?, ?, 1, 'COMPLETED', 90, ?,
                            'rule-set-v1', 'scoring-v1', 'feature-v1',
                            ?, ?, ?, 'trace_recovery_concurrency_01'
                        )
                        RETURNING id
                        """,
                Long.class,
                UUID.randomUUID(),
                transactionPk,
                riskLevel.name(),
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT.plusSeconds(1)),
                Timestamp.from(OCCURRED_AT.plusSeconds(2))
        );
        jdbcTemplate.update(
                """
                        UPDATE financial_transaction
                        SET processing_status = 'ANALYZED',
                            adopted_detection_result_id = ?,
                            risk_level = ?
                        WHERE id = ?
                        """,
                detectionPk,
                riskLevel.name(),
                transactionPk
        );
        long recordId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO idempotency_record (
                            operation_scope, idempotency_key,
                            request_fingerprint, processing_status,
                            financial_transaction_id
                        ) VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                        RETURNING id
                        """,
                Long.class,
                OPERATION_SCOPE,
                key,
                fingerprint,
                transactionPk
        );
        finalizationService.finalizeRiskResponse(input.transactionId());
        return new RecoveryFixture(recordId, input.transactionId());
    }

    private TransactionFingerprintInput fingerprintInput(UUID transactionId) {
        return new TransactionFingerprintInput(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(125_000),
                "KRW",
                OCCURRED_AT,
                "customer_ref_recovery_concurrent",
                "sender_ref_recovery_concurrent",
                "recipient_ref_recovery_concurrent",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_recovery_concurrent"
        );
    }

    private TransactionCreateRequest request(TransactionFingerprintInput input) {
        return new TransactionCreateRequest(
                input.transactionId().toString(),
                input.transactionType().name(),
                input.amount().toPlainString(),
                input.currencyCode(),
                input.occurredAt().toString(),
                input.externalCustomerRef(),
                input.senderAccountRef(),
                input.recipientAccountRef(),
                input.channel().name(),
                input.deviceRef()
        );
    }

    private StoredState storedState(RecoveryFixture fixture) {
        UUID transactionId = fixture.transactionId();
        return new StoredState(
                jdbcTemplate.queryForObject("""
                        SELECT to_jsonb(t)::text FROM financial_transaction t
                        WHERE t.transaction_id = ?
                        """, String.class, transactionId),
                jdbcTemplate.queryForObject("""
                        SELECT COALESCE(jsonb_agg(to_jsonb(d) ORDER BY d.id),
                            '[]'::jsonb)::text
                        FROM detection_result d
                        JOIN financial_transaction t
                          ON t.id = d.financial_transaction_id
                        WHERE t.transaction_id = ?
                        """, String.class, transactionId),
                jdbcTemplate.queryForObject("""
                        SELECT COALESCE(jsonb_agg(to_jsonb(e) ORDER BY e.id),
                            '[]'::jsonb)::text
                        FROM detection_evidence e
                        JOIN detection_result d ON d.id = e.detection_result_id
                        JOIN financial_transaction t
                          ON t.id = d.financial_transaction_id
                        WHERE t.transaction_id = ?
                        """, String.class, transactionId),
                jdbcTemplate.queryForObject("""
                        SELECT COALESCE(jsonb_agg(to_jsonb(c) ORDER BY c.id),
                            '[]'::jsonb)::text
                        FROM fraud_case c
                        JOIN case_transaction ct ON ct.fraud_case_id = c.id
                        JOIN financial_transaction t
                          ON t.id = ct.financial_transaction_id
                        WHERE t.transaction_id = ?
                        """, String.class, transactionId),
                jdbcTemplate.queryForObject("""
                        SELECT COALESCE(jsonb_agg(to_jsonb(ct) ORDER BY ct.id),
                            '[]'::jsonb)::text
                        FROM case_transaction ct
                        JOIN financial_transaction t
                          ON t.id = ct.financial_transaction_id
                        WHERE t.transaction_id = ?
                        """, String.class, transactionId),
                jdbcTemplate.queryForObject("""
                        SELECT to_jsonb(r)::text FROM idempotency_record r
                        WHERE r.id = ?
                        """, String.class, fixture.recordId()),
                jdbcTemplate.queryForObject("""
                        SELECT COALESCE(jsonb_agg(to_jsonb(a) ORDER BY a.id),
                            '[]'::jsonb)::text
                        FROM audit_log a
                        """, String.class));
    }

    private List<String> recoveryAudits(long recordId) {
        return jdbcTemplate.queryForList("""
                SELECT recovery_decision || ':' || audit_result
                FROM idempotency_recovery_audit_log
                WHERE idempotency_record_id = ? ORDER BY id
                """, String.class, recordId);
    }

    private String recoveryOption(String option) {
        return IdempotencyRecoveryCommandArguments.PREFIX + option;
    }

    private record StoredState(
            String transaction, String detection, String evidence,
            String fraudCase, String caseTransaction,
            String idempotency, String businessAudit
    ) {
    }

    private record RecoveryFixture(
            long recordId,
            UUID transactionId
    ) {
    }
}
