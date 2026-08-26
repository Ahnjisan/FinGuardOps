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
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
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
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

    @Autowired
    private ExternalRiskRuleAnalysisCoordinator coordinator;

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
