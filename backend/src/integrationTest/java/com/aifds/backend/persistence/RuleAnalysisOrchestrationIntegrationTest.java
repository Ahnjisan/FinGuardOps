package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.detection.service.CompletedRuleAnalysis;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private RuleAnalysisHttpClient httpClient;

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
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                Instant.now()
                        .minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS),
                "cust_ref_rule_orchestration",
                "acct_ref_rule_orchestration_sender",
                "acct_ref_rule_orchestration_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_rule_orchestration"
        ));
    }
}
