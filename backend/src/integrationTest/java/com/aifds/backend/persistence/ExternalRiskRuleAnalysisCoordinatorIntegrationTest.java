package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.detection.service.CompletedRuleAnalysis;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.mock.ExternalRiskMockAdapter;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleAnalysisResultResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.service.RuleVersionLifecycleService;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles({"test", "external-risk-mock"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "finguardops.external-risk.mock.enabled=true",
                "finguardops.external-risk.mock.scenario=UNMATCHED"
        }
)
class ExternalRiskRuleAnalysisCoordinatorIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String TRACE_ID =
            "trace_ext_risk_coordinator_integration";
    private static final String CONCURRENT_TRACE_ONE =
            "trace_ext_risk_coordinator_concurrent_01";
    private static final String CONCURRENT_TRACE_TWO =
            "trace_ext_risk_coordinator_concurrent_02";
    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";

    @Autowired
    private ExternalRiskRuleAnalysisCoordinator coordinator;

    @Autowired
    private TransactionIntakeService intakeService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private DetectionResultRepository resultRepository;

    @Autowired
    private DetectionEvidenceRepository evidenceRepository;

    @Autowired
    private RuleVersionRepository ruleVersionRepository;

    @Autowired
    private RuleVersionLifecycleService ruleVersionLifecycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private ExternalRiskMockAdapter externalRiskMockAdapter;

    @MockitoBean
    private RuleAnalysisHttpClient httpClient;

    @ParameterizedTest
    @EnumSource(RuleRiskLevel.class)
    void publicIntakeFinalizesEveryRiskLevelAndReplaysWithoutDownstream(
            RuleRiskLevel ruleRiskLevel
    ) {
        RuleVersion version = publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("public-" + ruleRiskLevel.name());
        String traceId = "trace_public_" + ruleRiskLevel.name().toLowerCase();
        when(httpClient.analyzeV2(any(), eq(traceId))).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        ruleRiskLevel,
                        traceId
                )
        );

        TransactionIntakeResult first = intakeService.receive(
                key,
                request,
                traceId
        );

        assertThat(first).isInstanceOf(TransactionIntakeResult.Received.class);
        TransactionIntakeResult.Received received =
                (TransactionIntakeResult.Received) first;
        assertFinalMapping(received, ruleRiskLevel);
        FinancialTransaction stored = transactionRepository
                .findByTransactionId(received.snapshot().transactionId())
                .orElseThrow();
        assertThat(stored.getAdoptedDetectionResult()).isNotNull();
        assertThat(results(stored.getTransactionId())).singleElement()
                .satisfies(result -> {
                    assertThat(result.getAnalysisStatus())
                            .isEqualTo(DetectionAnalysisStatus.COMPLETED);
                    assertThat(evidenceRepository
                            .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                                    result.getDetectionResultId()
                            )).hasSize(1);
                });
        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(
                        OPERATION_SCOPE,
                        key
                )
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        assertThat(record.getResponseSnapshot().get("responseSchemaVersion")
                .textValue()).isEqualTo("transaction-create-response-v2");
        assertThat(record.getResponseSnapshot().get("codecVersion")
                .textValue()).isEqualTo(
                "transaction-intake-snapshot-envelope-v2"
        );
        boolean caseRequired = ruleRiskLevel == RuleRiskLevel.HIGH
                || ruleRiskLevel == RuleRiskLevel.CRITICAL;
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fraud_case",
                Integer.class
        )).isEqualTo(caseRequired ? 1 : 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log",
                Integer.class
        )).isEqualTo(caseRequired ? 4 : 2);

        TransactionIntakeResult replay = intakeService.receive(
                key,
                request,
                traceId + "_replay"
        );
        assertThat(replay).isEqualTo(
                new TransactionIntakeResult.CompletedReplay(
                        received.snapshot(),
                        201
                )
        );
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), eq(traceId));
    }

    @Test
    void externalRiskFailureLeavesReceivedWithoutAnalysisWritesOrHttp() {
        FinancialTransaction transaction = saveTransaction();
        ExternalRiskLookupException original =
                new ExternalRiskLookupException(
                        ExternalRiskFailureCategory.TIMEOUT
                );
        AtomicBoolean externalRiskTransactionActive =
                new AtomicBoolean(true);
        doAnswer(invocation -> {
            externalRiskTransactionActive.set(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            throw original;
        }).when(externalRiskMockAdapter).lookup(any());

        Throwable thrown = catchThrowable(() -> coordinator
                .analyzeWithExternalRisk(
                        transaction.getTransactionId(),
                        TRACE_ID
                ));

        assertThat(thrown).isSameAs(original);
        assertThat(externalRiskTransactionActive).isFalse();
        assertThat(storedTransaction(transaction).getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(results(transaction.getTransactionId())).isEmpty();
        assertThat(evidenceCount()).isZero();
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, never()).analyzeV2(any(), anyString());
        verify(httpClient, never()).analyze(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(ExternalRiskFailureCategory.class)
    void publicIntakePersistsAndReplaysEveryTypedExternalRiskFailure(
            ExternalRiskFailureCategory category
    ) {
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("typed-" + category.name());
        ExternalRiskLookupException original =
                new ExternalRiskLookupException(category);
        doThrow(original).when(externalRiskMockAdapter).lookup(any());

        TransactionIntakeResult first = intakeService.receive(
                key,
                request,
                TRACE_ID
        );

        assertThat(first)
                .isInstanceOf(TransactionIntakeResult.ExternalRiskFailure.class);
        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getResponseSnapshot().get("snapshotType").textValue())
                .isEqualTo("external-risk-failure");
        assertThat(record.getResponseSnapshot().has("traceId")).isFalse();
        FinancialTransaction stored = transactionRepository
                .findByTransactionId(UUID.fromString(request.transactionId()))
                .orElseThrow();
        assertThat(stored.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(results(stored.getTransactionId())).isEmpty();
        assertThat(evidenceCount()).isZero();

        TransactionIntakeResult replay = intakeService.receive(
                key,
                request,
                TRACE_ID + "_replay"
        );
        assertThat(replay).isInstanceOf(
                TransactionIntakeResult.ExternalRiskFailureReplay.class
        );
        TransactionIntakeResult.ExternalRiskFailure initial =
                (TransactionIntakeResult.ExternalRiskFailure) first;
        TransactionIntakeResult.ExternalRiskFailureReplay replayed =
                (TransactionIntakeResult.ExternalRiskFailureReplay) replay;
        assertThat(replayed.httpStatus()).isEqualTo(initial.httpStatus());
        assertThat(replayed.code()).isEqualTo(initial.code());
        assertThat(replayed.message()).isEqualTo(initial.message());
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, never()).analyzeV2(any(), anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fraud_case",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log",
                Integer.class
        )).isZero();
    }

    @Test
    void confirmedRuleFailureBecomesCodeOnlyFailureAndReplaysWithoutCalls() {
        publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("rule-failed");
        RuntimeException original = new IllegalStateException("rule unavailable");
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenThrow(original);

        assertThat(intakeService.receive(key, request, TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.RuleFailure());
        FinancialTransaction stored = transactionRepository
                .findByTransactionId(UUID.fromString(request.transactionId()))
                .orElseThrow();
        assertThat(stored.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.FAILED);
        assertThat(results(stored.getTransactionId())).singleElement()
                .satisfies(result -> assertThat(result.getAnalysisStatus())
                        .isEqualTo(DetectionAnalysisStatus.FAILED));
        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getFailureCode())
                .isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(record.getResponseSnapshot()).isNull();

        assertThat(intakeService.receive(key, request, TRACE_ID + "_replay"))
                .isEqualTo(new TransactionIntakeResult.PreviousFailure(
                        "DEPENDENCY_UNAVAILABLE"
                ));
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), eq(TRACE_ID));
    }

    @Test
    void ruleStartFailureAtReceivedBecomesCodeOnlyFailure() {
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("rule-start-failed");

        assertThat(intakeService.receive(key, request, TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.RuleFailure());

        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(UUID.fromString(request.transactionId()))
                .orElseThrow();
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(results(transaction.getTransactionId())).isEmpty();
        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getFailureCode())
                .isEqualTo("DEPENDENCY_UNAVAILABLE");
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, never()).analyzeV2(any(), anyString());
    }

    @Test
    void finalizationFailureRollsBackAndKeepsAnalyzedInProgress() {
        RuleVersion version = publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("finalization-gap");
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        RuleRiskLevel.LOW,
                        TRACE_ID
                )
        );
        installAuditFailureTrigger();
        try {
            assertThatThrownBy(() -> intakeService.receive(
                    key,
                    request,
                    TRACE_ID
            )).isInstanceOf(RuntimeException.class);
        } finally {
            removeAuditFailureTrigger();
        }

        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(UUID.fromString(request.transactionId()))
                .orElseThrow();
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(transaction.getRiskResponseOutcome()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log",
                Integer.class
        )).isZero();
        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(record.getResponseSnapshot()).isNull();
    }

    @Test
    void completionFailureKeepsFinalBusinessStateAndInProgress() {
        RuleVersion version = publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("completion-gap");
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        RuleRiskLevel.LOW,
                        TRACE_ID
                )
        );
        installCompletionFailureTrigger();
        try {
            assertThatThrownBy(() -> intakeService.receive(
                    key,
                    request,
                    TRACE_ID
            )).isInstanceOf(RuntimeException.class);
        } finally {
            removeCompletionFailureTrigger();
        }

        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(UUID.fromString(request.transactionId()))
                .orElseThrow();
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.APPROVED);
        assertThat(transaction.getRiskResponseOutcome().name())
                .isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log",
                Integer.class
        )).isEqualTo(2);
        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(record.getResponseSnapshot()).isNull();
        assertThat(record.getFinishedAt()).isNull();
    }

    @Test
    void concurrentSameRequestHasOneProviderWinnerAndConflictSkipsProvider()
            throws Exception {
        RuleVersion version = publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        String key = key("public-concurrent");
        CountDownLatch providerReached = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            providerReached.countDown();
            assertThat(releaseProvider.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(externalRiskMockAdapter).lookup(any());
        when(httpClient.analyzeV2(any(), eq(CONCURRENT_TRACE_ONE))).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        RuleRiskLevel.LOW,
                        CONCURRENT_TRACE_ONE
                )
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TransactionIntakeResult> winner = executor.submit(
                    () -> intakeService.receive(
                            key,
                            request,
                            CONCURRENT_TRACE_ONE
                    )
            );
            assertThat(providerReached.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(intakeService.receive(
                    key,
                    request,
                    CONCURRENT_TRACE_TWO
            )).isEqualTo(new TransactionIntakeResult.InProgress());
            TransactionCreateRequest different = request(
                    UUID.fromString(request.transactionId()),
                    "10000001"
            );
            assertThat(intakeService.receive(
                    key,
                    different,
                    CONCURRENT_TRACE_TWO
            )).isEqualTo(new TransactionIntakeResult.KeyConflict());

            releaseProvider.countDown();
            assertThat(winner.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(TransactionIntakeResult.Received.class);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(
                any(),
                eq(CONCURRENT_TRACE_ONE)
        );
    }

    @Test
    void differentKeySameTransactionIsDuplicateBeforeSecondProviderCall() {
        RuleVersion version = publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID(), "10000000");
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        RuleRiskLevel.LOW,
                        TRACE_ID
                )
        );

        assertThat(intakeService.receive(
                key("duplicate-first"),
                request,
                TRACE_ID
        )).isInstanceOf(TransactionIntakeResult.Received.class);
        assertThat(intakeService.receive(
                key("duplicate-second"),
                request,
                TRACE_ID + "_duplicate"
        )).isEqualTo(new TransactionIntakeResult.DuplicateTransaction(
                UUID.fromString(request.transactionId())
        ));

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), eq(TRACE_ID));
    }

    @Test
    void successCallsExternalRiskAndV2HttpOutsideTransactionsAndAdoptsResult() {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        AtomicBoolean externalRiskTransactionActive =
                new AtomicBoolean(true);
        AtomicBoolean httpTransactionActive = new AtomicBoolean(true);
        doAnswer(invocation -> {
            externalRiskTransactionActive.set(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return invocation.callRealMethod();
        }).when(externalRiskMockAdapter).lookup(any());
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(
                invocation -> {
                    httpTransactionActive.set(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive()
                    );
                    return response(
                            transaction,
                            version,
                            TRACE_ID
                    );
                }
        );

        CompletedRuleAnalysis completed = coordinator
                .analyzeWithExternalRisk(
                        transaction.getTransactionId(),
                        TRACE_ID
                );

        assertThat(externalRiskTransactionActive).isFalse();
        assertThat(httpTransactionActive).isFalse();
        assertCompletedState(completed, 15, RiskLevel.MEDIUM, 1);
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), eq(TRACE_ID));
        verify(httpClient, never()).analyze(any(), anyString());
    }

    @Test
    void clientFailureUsesExistingFailedPersistenceBoundary() {
        FinancialTransaction transaction = saveTransaction();
        publishAmountRule();
        RuntimeException original = new IllegalStateException(
                "v2 client failed"
        );
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenThrow(original);

        Throwable thrown = catchThrowable(() -> coordinator
                .analyzeWithExternalRisk(
                        transaction.getTransactionId(),
                        TRACE_ID
                ));

        assertThat(thrown).isSameAs(original);
        assertFailedState(
                transaction.getTransactionId(),
                "RULE_ANALYSIS_HTTP_CALL_FAILED"
        );
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), eq(TRACE_ID));
        verify(httpClient, never()).analyze(any(), anyString());
    }

    @Test
    void concurrentDirectCallsAllowOneRuleAnalysisStartWinner() throws Exception {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        CountDownLatch bothExternalCallsReached = new CountDownLatch(2);
        CountDownLatch releaseExternalCalls = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            bothExternalCallsReached.countDown();
            assertThat(releaseExternalCalls.await(10, TimeUnit.SECONDS))
                    .isTrue();
            return invocation.callRealMethod();
        }).when(externalRiskMockAdapter).lookup(any());
        when(httpClient.analyzeV2(any(), anyString())).thenAnswer(
                invocation -> response(
                        transaction,
                        version,
                        invocation.getArgument(1)
                )
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CompletedRuleAnalysis> first = executor.submit(
                    () -> coordinator.analyzeWithExternalRisk(
                            transaction.getTransactionId(),
                            CONCURRENT_TRACE_ONE
                    )
            );
            Future<CompletedRuleAnalysis> second = executor.submit(
                    () -> coordinator.analyzeWithExternalRisk(
                            transaction.getTransactionId(),
                            CONCURRENT_TRACE_TWO
                    )
            );
            assertThat(bothExternalCallsReached.await(10, TimeUnit.SECONDS))
                    .isTrue();
            releaseExternalCalls.countDown();

            List<Object> outcomes = List.of(
                    outcome(first),
                    outcome(second)
            );
            assertThat(outcomes.stream()
                    .filter(CompletedRuleAnalysis.class::isInstance)
                    .count()).isEqualTo(1);
            assertThat(outcomes.stream()
                    .filter(Throwable.class::isInstance)
                    .count()).isEqualTo(1);
        } finally {
            releaseExternalCalls.countDown();
            executor.shutdownNow();
        }

        assertThat(results(transaction.getTransactionId())).hasSize(1);
        assertThat(storedTransaction(transaction).getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        verify(externalRiskMockAdapter, times(2)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), anyString());
        verify(httpClient, never()).analyze(any(), anyString());
    }

    private Object outcome(Future<CompletedRuleAnalysis> future)
            throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private void assertFinalMapping(
            TransactionIntakeResult.Received received,
            RuleRiskLevel riskLevel
    ) {
        assertThat(received.httpStatus()).isEqualTo(201);
        assertThat(received.snapshot().riskLevel())
                .isEqualTo(riskLevel.name());
        switch (riskLevel) {
            case LOW -> {
                assertThat(received.snapshot().processingStatus())
                        .isEqualTo(TransactionProcessingStatus.APPROVED);
                assertThat(received.snapshot().riskResponseOutcome())
                        .isEqualTo("APPROVED");
                assertThat(received.snapshot().caseId()).isNull();
            }
            case MEDIUM -> {
                assertThat(received.snapshot().processingStatus())
                        .isEqualTo(TransactionProcessingStatus.APPROVED);
                assertThat(received.snapshot().riskResponseOutcome())
                        .isEqualTo("APPROVED_WITH_MONITORING");
                assertThat(received.snapshot().caseId()).isNull();
            }
            case HIGH -> {
                assertThat(received.snapshot().processingStatus()).isEqualTo(
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                );
                assertThat(received.snapshot().riskResponseOutcome())
                        .isEqualTo("ADDITIONAL_AUTH_REQUIRED");
                assertThat(received.snapshot().caseId()).isNotNull();
            }
            case CRITICAL -> {
                assertThat(received.snapshot().processingStatus())
                        .isEqualTo(TransactionProcessingStatus.HELD);
                assertThat(received.snapshot().riskResponseOutcome())
                        .isEqualTo("HELD");
                assertThat(received.snapshot().caseId()).isNotNull();
            }
        }
        assertThat(received.snapshot().adoptedDetectionResultId()).isNotNull();
    }

    private RuleAnalysisResponse response(
            RuleAnalysisRequestV2 request,
            RuleVersion version,
            RuleRiskLevel riskLevel,
            String traceId
    ) {
        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(request.transaction().transactionId())
                .orElseThrow();
        int riskScore = switch (riskLevel) {
            case LOW -> 0;
            case MEDIUM -> 15;
            case HIGH -> 55;
            case CRITICAL -> 85;
        };
        return new RuleAnalysisResponse(
                request.transaction().transactionId(),
                traceId,
                new RuleAnalysisResultResponse(
                        request.evaluationCutoffAt(),
                        "a".repeat(64),
                        new RuleScoringResultResponse(
                                "scoring-policy-v1",
                                riskScore,
                                riskLevel,
                                List.of(),
                                List.of()
                        ),
                        List.of(amountEvidence(transaction, version))
                )
        );
    }

    private TransactionCreateRequest request(UUID transactionId, String amount) {
        return new TransactionCreateRequest(
                transactionId.toString(),
                "ACCOUNT_TRANSFER",
                amount,
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS).toString(),
                "customer_ref_public_intake",
                "sender_ref_public_intake",
                "recipient_ref_public_intake",
                "MOBILE_BANKING",
                "device_ref_public_intake"
        );
    }

    private String key(String prefix) {
        return prefix.toLowerCase() + "-" + UUID.randomUUID();
    }

    private void installAuditFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_audit_finalization_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced audit finalization failure'
                        USING ERRCODE = 'P0001';
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_fail_audit_finalization_test
                BEFORE INSERT ON audit_log
                FOR EACH ROW
                EXECUTE FUNCTION fail_audit_finalization_test()
                """);
    }

    private void removeAuditFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_fail_audit_finalization_test
                ON audit_log
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS fail_audit_finalization_test()"
        );
    }

    private void installCompletionFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_v2_completion_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.processing_status = 'COMPLETED' THEN
                        RAISE EXCEPTION 'forced v2 completion failure'
                            USING ERRCODE = 'P0001';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_fail_v2_completion_test
                BEFORE UPDATE ON idempotency_record
                FOR EACH ROW
                EXECUTE FUNCTION fail_v2_completion_test()
                """);
    }

    private void removeCompletionFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_fail_v2_completion_test
                ON idempotency_record
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS fail_v2_completion_test()"
        );
    }

    private void assertCompletedState(
            CompletedRuleAnalysis completed,
            int riskScore,
            RiskLevel riskLevel,
            int expectedEvidenceCount
    ) {
        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(completed.transactionId())
                .orElseThrow();
        DetectionResult result = resultRepository
                .findByDetectionResultId(completed.detectionResultId())
                .orElseThrow();
        List<DetectionEvidence> evidence = evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        completed.detectionResultId()
                );
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(transaction.getAdoptedDetectionResult())
                .isNotNull();
        assertThat(transaction.getRiskLevel()).isEqualTo(riskLevel);
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.COMPLETED);
        assertThat(result.getRiskScore()).isEqualTo(riskScore);
        assertThat(result.getRiskLevel()).isEqualTo(riskLevel);
        assertThat(evidence).hasSize(expectedEvidenceCount);
    }

    private void assertFailedState(UUID transactionId, String failureCode) {
        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(transactionId)
                .orElseThrow();
        List<DetectionResult> results = results(transactionId);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.FAILED);
        assertThat(transaction.getAdoptedDetectionResult()).isNull();
        assertThat(transaction.getRiskLevel()).isNull();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getAnalysisStatus())
                    .isEqualTo(DetectionAnalysisStatus.FAILED);
            assertThat(result.getFailureCode()).isEqualTo(failureCode);
            assertThat(evidenceRepository
                    .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                            result.getDetectionResultId()
                    )).isEmpty();
        });
    }

    private FinancialTransaction storedTransaction(
            FinancialTransaction transaction
    ) {
        return transactionRepository.findByTransactionId(
                transaction.getTransactionId()
        ).orElseThrow();
    }

    private List<DetectionResult> results(UUID transactionId) {
        return resultRepository
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        transactionId
                );
    }

    private int evidenceCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detection_evidence",
                Integer.class
        );
    }

    private RuleAnalysisResponse response(
            FinancialTransaction transaction,
            RuleVersion version,
            String traceId
    ) {
        return new RuleAnalysisResponse(
                transaction.getTransactionId(),
                traceId,
                new RuleAnalysisResultResponse(
                        transaction.getOccurredAt(),
                        "a".repeat(64),
                        new RuleScoringResultResponse(
                                "scoring-policy-v1",
                                15,
                                RuleRiskLevel.MEDIUM,
                                List.of(),
                                List.of()
                        ),
                        List.of(amountEvidence(transaction, version))
                )
        );
    }

    private RuleEvidenceResponse amountEvidence(
            FinancialTransaction transaction,
            RuleVersion version
    ) {
        return new RuleEvidenceResponse(
                RuleId.R001,
                version.getRuleVersionId(),
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                Integer.toString(version.getVersionNumber()),
                version.getReasonCode(),
                1,
                version.getWeight(),
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000"),
                transaction.getOccurredAt()
        );
    }

    private RuleVersion publishAmountRule() {
        RuleVersion version = amountRule();
        if (version.getStatus() != RuleVersionStatus.PUBLISHED) {
            ruleVersionLifecycleService.updateDraft(
                    version.getRuleVersionId(),
                    version.getReasonCode(),
                    version.getWeight(),
                    version.getConditionDefinition(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null
            );
            ruleVersionLifecycleService.publish(
                    version.getRuleVersionId(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS)
            );
        }
        return amountRule();
    }

    private RuleVersion amountRule() {
        return ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        1
                ).orElseThrow();
    }

    private FinancialTransaction saveTransaction() {
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                Instant.now()
                        .minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS),
                "cust_ref_ext_risk_coordinator",
                "acct_ref_ext_risk_coordinator_sender",
                "acct_ref_ext_risk_coordinator_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_ext_risk_coordinator"
        ));
    }
}
