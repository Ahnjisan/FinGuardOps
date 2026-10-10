package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.ml.MlDetectionPolicy;
import com.aifds.backend.detection.ml.MlDetectionService;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.detection.service.CompletedRuleAnalysis;
import com.aifds.backend.detection.service.AdoptedDetectionResultQueryService;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.RuleAnalysisResponseValidator;
import com.aifds.backend.rule.client.config.RuleAnalysisClientConfiguration;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
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
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuleAnalysisOrchestrationIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String TRACE_ID =
            "trace_rule_orchestration_integration";

    @Autowired
    private RuleAnalysisOrchestrationService orchestrationService;

    @Autowired
    private AdoptedDetectionResultQueryService adoptedQuery;

    @Autowired
    private RiskResponseFinalizationService riskResponseFinalizationService;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private BehaviorEventRepository behaviorEventRepository;

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

    @Autowired
    @Qualifier(RuleAnalysisClientConfiguration.OBJECT_MAPPER_BEAN)
    private ObjectMapper ruleWireMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private RuleAnalysisHttpClient httpClient;

    @MockitoBean
    private MlDetectionService mlService;

    @Test
    void callsClientAfterStartCommitWithoutAnActiveTransactionAndAdoptsResult() {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicReference<Map<String, Object>> committedState =
                new AtomicReference<>();
        when(httpClient.analyze(any(), eq(TRACE_ID))).thenAnswer(invocation -> {
            transactionActive.set(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            committedState.set(jdbcTemplate.queryForMap("""
                    SELECT
                        tx.processing_status,
                        result.analysis_status
                    FROM financial_transaction tx
                    JOIN detection_result result
                      ON result.financial_transaction_id = tx.id
                    WHERE tx.transaction_id = ?
                    """, transaction.getTransactionId()));
            return response(transaction, version, List.of(
                    amountEvidence(transaction, version)
            ), 15, RuleRiskLevel.MEDIUM);
        });

        CompletedRuleAnalysis completed = orchestrationService.analyze(
                transaction.getTransactionId(),
                TRACE_ID
        );

        assertThat(transactionActive).isFalse();
        assertThat(committedState.get())
                .containsEntry("processing_status", "ANALYZING")
                .containsEntry("analysis_status", "IN_PROGRESS");
        assertCompletedState(completed, 15, RiskLevel.MEDIUM, 1);
        verify(httpClient, times(1)).analyze(any(), eq(TRACE_ID));
    }

    @Test
    void v2CallsOnlyV2ClientAfterCommitAndDoesNotScoreExternalRisk() {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        ExternalRiskSnapshot externalRisk = externalRiskSnapshot(transaction);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicReference<Map<String, Object>> committedState =
                new AtomicReference<>();
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(invocation -> {
            transactionActive.set(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            committedState.set(jdbcTemplate.queryForMap("""
                    SELECT
                        tx.processing_status,
                        result.analysis_status
                    FROM financial_transaction tx
                    JOIN detection_result result
                      ON result.financial_transaction_id = tx.id
                    WHERE tx.transaction_id = ?
                    """, transaction.getTransactionId()));
            return response(transaction, version, List.of(
                    amountEvidence(transaction, version)
            ), 15, RuleRiskLevel.MEDIUM);
        });

        CompletedRuleAnalysis completed = orchestrationService.analyzeV2(
                transaction.getTransactionId(),
                externalRisk,
                TRACE_ID
        );

        assertThat(transactionActive).isFalse();
        assertThat(committedState.get())
                .containsEntry("processing_status", "ANALYZING")
                .containsEntry("analysis_status", "IN_PROGRESS");
        assertCompletedState(completed, 15, RiskLevel.MEDIUM, 1);
        ArgumentCaptor<RuleAnalysisRequestV2> requestCaptor =
                ArgumentCaptor.forClass(RuleAnalysisRequestV2.class);
        verify(httpClient, times(1)).analyzeV2(
                requestCaptor.capture(),
                eq(TRACE_ID)
        );
        verify(httpClient, never()).analyze(any(), any());
        assertThat(requestCaptor.getValue().externalRisk().providerCode())
                .isEqualTo(externalRisk.providerCode());
        assertThat(completed.riskScore()).isEqualTo(15);
        assertThat(completed.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void v2CombinesRuleClientResultWithPinnedMlAndPersistsBothEvidenceTypes() {
        FinancialTransaction transaction = saveTransaction(
                UUID.fromString("00000000-0000-4000-8000-000000000380"));
        RuleVersion version = publishAmountRule();
        ExternalRiskSnapshot externalRisk = externalRiskSnapshot(transaction);
        when(mlService.cutoffFor(transaction.getTransactionId()))
                .thenReturn(transaction.getOccurredAt());
        when(mlService.appliesAt(transaction.getOccurredAt())).thenReturn(true);
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenReturn(response(
                transaction, version, List.of(amountEvidence(transaction, version)),
                15, RuleRiskLevel.LOW));
        when(mlService.infer(transaction.getTransactionId(), transaction.getOccurredAt(),
                MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                .thenReturn(new MlDetectionService.MlResult(10000, "ML_RISK_SIGNAL"));

        CompletedRuleAnalysis completed = orchestrationService.analyzeV2(
                transaction.getTransactionId(), externalRisk, TRACE_ID);

        assertCompletedState(completed, 55, RiskLevel.HIGH, 2);
        DetectionResult result = resultRepository.findByDetectionResultId(
                completed.detectionResultId()).orElseThrow();
        assertThat(result.getScoringPolicyVersion()).isEqualTo(MlDetectionPolicy.POLICY_VERSION);
        assertThat(result.getModelVersion()).isEqualTo(MlDetectionPolicy.MODEL_VERSION);
        assertThat(result.getModelSha256()).isEqualTo(MlDetectionPolicy.MODEL_SHA256);
        assertThat(result.getRuleRiskScore()).isEqualTo(15);
        assertThat(result.getMlContribution()).isEqualTo(40);
        assertThat(result.getMlProbabilityBasisPoints()).isEqualTo(10000);
        assertThat(evidenceRepository.findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                completed.detectionResultId())).extracting(e -> e.getEvidenceType().name())
                .containsExactly("RULE", "ML");
        var queried = adoptedQuery.find(transaction.getTransactionId().toString());
        assertThat(queried.adoptedResult().ruleScore()).isEqualTo(15);
        assertThat(queried.adoptedResult().mlContribution()).isEqualTo(40);
        assertThat(queried.adoptedResult().riskScore()).isEqualTo(55);
        assertThat(queried.adoptedResult().mlEvidence()).hasSize(1);
        var finalized = riskResponseFinalizationService.finalizeRiskResponse(
                transaction.getTransactionId());
        assertThat(finalized.processingStatus())
                .isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(finalized.caseId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT result.detection_result_id FROM financial_transaction tx
                JOIN detection_result result ON result.id = tx.adopted_detection_result_id
                WHERE tx.transaction_id = ?
                """, UUID.class, transaction.getTransactionId()))
                .isEqualTo(completed.detectionResultId());
        assertThat(adoptedQuery.find(transaction.getTransactionId().toString())
                .adoptedResult().riskScore()).isEqualTo(55);
    }

    @Test
    void mlFailureKeepsTransactionAndResultFailedWithoutAdoptionOrEvidence() {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        when(mlService.cutoffFor(transaction.getTransactionId()))
                .thenReturn(transaction.getOccurredAt());
        when(mlService.appliesAt(transaction.getOccurredAt())).thenReturn(true);
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenReturn(response(
                transaction, version, List.of(amountEvidence(transaction, version)),
                15, RuleRiskLevel.LOW));
        when(mlService.infer(transaction.getTransactionId(), transaction.getOccurredAt(),
                MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                .thenThrow(new MlDetectionService.MlDetectionException("ML_SERVICE_UNAVAILABLE"));

        assertThatThrownBy(() -> orchestrationService.analyzeV2(
                transaction.getTransactionId(), externalRiskSnapshot(transaction), TRACE_ID))
                .isInstanceOf(MlDetectionService.MlDetectionException.class);
        assertFailedState(transaction.getTransactionId(), "ML_SERVICE_UNAVAILABLE");
        assertThat(adoptedQuery.find(transaction.getTransactionId().toString())
                .latestFailureCode()).isEqualTo("ML_SERVICE_UNAVAILABLE");
        assertThat(adoptedQuery.find(transaction.getTransactionId().toString())
                .adoptedResult()).isNull();
        assertThatThrownBy(() -> riskResponseFinalizationService.finalizeRiskResponse(
                transaction.getTransactionId())).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForList("""
                SELECT action FROM audit_log WHERE transaction_id = ?
                """, String.class, transaction.getTransactionId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM case_transaction ct
                JOIN financial_transaction tx ON tx.id = ct.financial_transaction_id
                WHERE tx.transaction_id = ?
                """, Integer.class, transaction.getTransactionId())).isZero();
    }

    @Test
    void liveLocalFastApiInferenceCanBeAdoptedWithTheSameTransactionId() {
        String url = System.getenv("FINGUARDOPS_LIVE_ML_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Run only with an owned local FastAPI process");
        Instant eventAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        List<BehaviorEvent> eventRows = new java.util.ArrayList<>();
        for (BehaviorEventType type : List.of(BehaviorEventType.DEVICE_REGISTERED,
                BehaviorEventType.PASSWORD_CHANGED,
                BehaviorEventType.TRANSFER_LIMIT_CHANGED,
                BehaviorEventType.BENEFICIARY_REGISTERED)) {
            for (int index = 0; index < 3; index++) {
                eventRows.add(new BehaviorEvent(UUID.randomUUID(), type, eventAt,
                        "cust_ref_rule_orchestration",
                        type == BehaviorEventType.DEVICE_REGISTERED ? null
                                : "acct_ref_rule_orchestration_sender",
                        type == BehaviorEventType.DEVICE_REGISTERED
                                ? "device_ref_rule_orchestration" : null,
                        type == BehaviorEventType.BENEFICIARY_REGISTERED
                                ? "acct_ref_rule_orchestration_recipient" : null,
                        null, "b".repeat(64)));
            }
        }
        behaviorEventRepository.saveAllAndFlush(eventRows);
        Instant cutoff = Instant.now().plusMillis(500).truncatedTo(ChronoUnit.MICROS);
        try {
            Thread.sleep(600);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        FinancialTransaction transaction = saveTransaction(
                UUID.fromString("00000000-0000-4000-8000-000000000382"), cutoff);
        publishAmountRule();
        MlDetectionService live = new MlDetectionService(true, Instant.EPOCH, url,
                transactionRepository, behaviorEventRepository, ruleWireMapper);
        RuleAnalysisHttpClient liveRule = new RuleAnalysisHttpClient(
                RestClient.builder().baseUrl(url)
                        .messageConverters(converters -> {
                            converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                            converters.add(0, new MappingJackson2HttpMessageConverter(ruleWireMapper));
                        }).build(),
                ruleWireMapper, new RuleAnalysisResponseValidator(), Duration.ofSeconds(3));
        when(mlService.cutoffFor(transaction.getTransactionId()))
                .thenReturn(transaction.getOccurredAt());
        when(mlService.appliesAt(transaction.getOccurredAt())).thenReturn(true);
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(invocation ->
                liveRule.analyzeV2(invocation.getArgument(0), TRACE_ID));
        when(mlService.infer(transaction.getTransactionId(), transaction.getOccurredAt(),
                MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                .thenAnswer(invocation -> live.infer(transaction.getTransactionId(),
                        transaction.getOccurredAt(), MlDetectionPolicy.POLICY_VERSION,
                        MlDetectionPolicy.MODEL_VERSION));

        CompletedRuleAnalysis completed = orchestrationService.analyzeV2(
                transaction.getTransactionId(), externalRiskSnapshot(transaction), TRACE_ID);
        var queried = adoptedQuery.find(transaction.getTransactionId().toString());
        assertThat(queried.adoptedResult().detectionResultId())
                .isEqualTo(completed.detectionResultId());
        assertThat(queried.adoptedResult().modelSha256())
                .isEqualTo(MlDetectionPolicy.MODEL_SHA256);
        assertThat(queried.adoptedResult().mlEvidence()).hasSize(1);
        assertThat(queried.adoptedResult().riskScore())
                .isEqualTo(MlDetectionPolicy.finalScore(
                        queried.adoptedResult().ruleScore(),
                        queried.adoptedResult().mlContribution()));
        assertThat(queried.adoptedResult().ruleScore()).isEqualTo(15);
        assertThat(queried.adoptedResult().mlContribution()).isEqualTo(35);
        assertThat(queried.adoptedResult().riskScore()).isEqualTo(50);
        var finalized = riskResponseFinalizationService.finalizeRiskResponse(
                transaction.getTransactionId());
        assertThat(finalized.adoptedDetectionResultId())
                .isEqualTo(completed.detectionResultId());
        assertThat(finalized.processingStatus())
                .isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(finalized.caseId()).isNotNull();
        assertThat(adoptedQuery.find(transaction.getTransactionId().toString())
                .adoptedResult().riskScore()).isEqualTo(completed.riskScore());
    }

    @Test
    void v2ClientFailureUsesTheExistingFailedPersistenceBoundary() {
        FinancialTransaction transaction = saveTransaction();
        publishAmountRule();
        ExternalRiskSnapshot externalRisk = externalRiskSnapshot(transaction);
        RuntimeException original = new IllegalStateException(
                "v2 client failed"
        );
        when(httpClient.analyzeV2(any(), eq(TRACE_ID))).thenThrow(original);

        assertThatThrownBy(() -> orchestrationService.analyzeV2(
                transaction.getTransactionId(),
                externalRisk,
                TRACE_ID
        )).isSameAs(original);

        assertFailedState(
                transaction.getTransactionId(),
                "RULE_ANALYSIS_HTTP_CALL_FAILED"
        );
        verify(httpClient, times(1)).analyzeV2(any(), eq(TRACE_ID));
        verify(httpClient, never()).analyze(any(), any());
    }

    @Test
    void v2MapperFailureRollsBackBeforeHttpAndFailurePersistence() {
        FinancialTransaction transaction = saveTransaction();
        publishAmountRule();
        ExternalRiskSnapshot mismatched = new ExternalRiskSnapshot(
                UUID.randomUUID(),
                transaction.getOccurredAt(),
                transaction.getOccurredAt().plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                transaction.getOccurredAt().minusSeconds(1),
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        );

        assertThatThrownBy(() -> orchestrationService.analyzeV2(
                transaction.getTransactionId(),
                mismatched,
                TRACE_ID
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThat(transactionRepository.findByTransactionId(
                transaction.getTransactionId()
        ).orElseThrow().getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(resultRepository
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        transaction.getTransactionId()
                )).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detection_evidence",
                Integer.class
        )).isZero();
        verify(httpClient, never()).analyzeV2(any(), any());
        verify(httpClient, never()).analyze(any(), any());
    }

    @Test
    void adoptsValidatedZeroLowResultWithEmptyEvidence() {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        when(httpClient.analyze(any(), eq(TRACE_ID))).thenReturn(response(
                transaction,
                version,
                List.of(),
                0,
                RuleRiskLevel.LOW
        ));

        CompletedRuleAnalysis completed = orchestrationService.analyze(
                transaction.getTransactionId(),
                TRACE_ID
        );

        assertCompletedState(completed, 0, RiskLevel.LOW, 0);
        verify(httpClient, times(1)).analyze(any(), eq(TRACE_ID));
    }

    @Test
    void recordsClientAndMappingFailuresWithoutEvidenceOrAdoption() {
        FinancialTransaction clientTransaction = saveTransaction();
        publishAmountRule();
        RuntimeException clientFailure = new IllegalStateException(
                "client failed"
        );
        when(httpClient.analyze(any(), eq(TRACE_ID)))
                .thenThrow(clientFailure);

        assertThatThrownBy(() -> orchestrationService.analyze(
                clientTransaction.getTransactionId(),
                TRACE_ID
        )).isSameAs(clientFailure);
        assertFailedState(
                clientTransaction.getTransactionId(),
                "RULE_ANALYSIS_HTTP_CALL_FAILED"
        );
        verify(httpClient, times(1)).analyze(any(), eq(TRACE_ID));

        org.mockito.Mockito.reset(httpClient);
        FinancialTransaction mappingTransaction = saveTransaction();
        RuleVersion version = amountRule();
        RuleEvidenceResponse unsupported = new RuleEvidenceResponse(
                RuleId.R001,
                version.getRuleVersionId(),
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                Integer.toString(version.getVersionNumber()),
                "UNSUPPORTED_REASON",
                1,
                version.getWeight(),
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000"),
                mappingTransaction.getOccurredAt()
        );
        when(httpClient.analyze(any(), eq(TRACE_ID))).thenReturn(response(
                mappingTransaction,
                version,
                List.of(unsupported),
                15,
                RuleRiskLevel.MEDIUM
        ));

        assertThatThrownBy(() -> orchestrationService.analyze(
                mappingTransaction.getTransactionId(),
                TRACE_ID
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_REASON");
        assertFailedState(
                mappingTransaction.getTransactionId(),
                "RULE_ANALYSIS_RESPONSE_MAPPING_FAILED"
        );
        verify(httpClient, times(1)).analyze(any(), eq(TRACE_ID));
    }

    @Test
    void rollsBackFlushedEvidenceBeforeRecordingAdoptionFailure() {
        FinancialTransaction transaction = saveTransaction();
        RuleVersion version = publishAmountRule();
        RuleEvidenceResponse evidence = amountEvidence(transaction, version);
        when(httpClient.analyze(any(), eq(TRACE_ID))).thenReturn(response(
                transaction,
                version,
                List.of(evidence, evidence),
                15,
                RuleRiskLevel.MEDIUM
        ));

        assertThatThrownBy(() -> orchestrationService.analyze(
                transaction.getTransactionId(),
                TRACE_ID
        )).isInstanceOf(RuntimeException.class);

        assertFailedState(
                transaction.getTransactionId(),
                "RULE_ANALYSIS_ADOPTION_FAILED"
        );
        verify(httpClient, times(1)).analyze(any(), eq(TRACE_ID));
    }

    @Test
    void rejectsAnOuterTransactionBeforeCreatingAnAnalysisAttempt() {
        FinancialTransaction transaction = saveTransaction();
        publishAmountRule();
        TransactionTemplate outer = new TransactionTemplate(
                transactionManager
        );

        assertThatThrownBy(() -> outer.execute(status ->
                orchestrationService.analyze(
                        transaction.getTransactionId(),
                        TRACE_ID
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");

        FinancialTransaction stored = transactionRepository
                .findByTransactionId(transaction.getTransactionId())
                .orElseThrow();
        assertThat(stored.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(results(transaction.getTransactionId())).isEmpty();
        verify(httpClient, never()).analyze(any(), any());
    }

    private void assertCompletedState(
            CompletedRuleAnalysis completed,
            int riskScore,
            RiskLevel riskLevel,
            int evidenceCount
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
        assertThat(completed.riskScore()).isEqualTo(riskScore);
        assertThat(completed.riskLevel()).isEqualTo(riskLevel);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT result.detection_result_id
                FROM financial_transaction tx
                JOIN detection_result result
                  ON result.id = tx.adopted_detection_result_id
                WHERE tx.transaction_id = ?
                """, UUID.class, completed.transactionId()))
                .isEqualTo(completed.detectionResultId());
        assertThat(transaction.getRiskLevel()).isEqualTo(riskLevel);
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.COMPLETED);
        assertThat(result.getRiskScore()).isEqualTo(riskScore);
        assertThat(result.getRiskLevel()).isEqualTo(riskLevel);
        assertThat(evidence).hasSize(evidenceCount);
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

    private List<DetectionResult> results(UUID transactionId) {
        return resultRepository
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        transactionId
                );
    }

    private RuleAnalysisResponse response(
            FinancialTransaction transaction,
            RuleVersion version,
            List<RuleEvidenceResponse> evidence,
            int riskScore,
            RuleRiskLevel riskLevel
    ) {
        return new RuleAnalysisResponse(
                transaction.getTransactionId(),
                TRACE_ID,
                new RuleAnalysisResultResponse(
                        transaction.getOccurredAt(),
                        "a".repeat(64),
                        new RuleScoringResultResponse(
                                "scoring-policy-v1",
                                riskScore,
                                riskLevel,
                                List.of(),
                                List.of()
                        ),
                        evidence
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
        return saveTransaction(UUID.randomUUID());
    }

    private FinancialTransaction saveTransaction(UUID transactionId) {
        return saveTransaction(transactionId, Instant.now()
                .minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));
    }

    private FinancialTransaction saveTransaction(UUID transactionId, Instant occurredAt) {
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                occurredAt,
                "cust_ref_rule_orchestration",
                "acct_ref_rule_orchestration_sender",
                "acct_ref_rule_orchestration_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_rule_orchestration"
        ));
    }

    private ExternalRiskSnapshot externalRiskSnapshot(
            FinancialTransaction transaction
    ) {
        return new ExternalRiskSnapshot(
                transaction.getTransactionId(),
                transaction.getOccurredAt(),
                transaction.getOccurredAt().plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                transaction.getOccurredAt().minusSeconds(1),
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        );
    }
}
