package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.service.AdoptedDetectionResultQueryService;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.detection.ml.MlDetectionService;
import com.aifds.backend.detection.ml.MlDetectionPolicy;
import com.aifds.backend.externalrisk.domain.*;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.*;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService;
import com.aifds.backend.rule.service.RuleV2LocalPublicationService;
import com.aifds.backend.rule.service.RuleV3LocalPublicationService;
import com.aifds.backend.transaction.entity.*;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Scn003DetectionIntegrationTest extends PostgresqlIntegrationTestSupport {
    private static final String TRACE_ID = "trace_scn003_integration_0001";

    @Autowired RuleV1DefaultRuleSetPublicationService v1;
    @Autowired RuleV2LocalPublicationService v2;
    @Autowired RuleV3LocalPublicationService v3;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired RuleAnalysisOrchestrationService orchestration;
    @Autowired DetectionEvidenceRepository evidence;
    @Autowired AdoptedDetectionResultQueryService adopted;
    @Autowired RiskResponseFinalizationService finalization;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RuleAnalysisHttpClient ruleClient;
    @MockitoBean MlDetectionService mlService;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void r005OnlyAndR005WithMlPersistOneAdoptedResult(boolean mlEnabled) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant v1At = now.plusSeconds(30);
        Instant v2At = now.plusSeconds(60);
        Instant v3At = now.plusSeconds(90);
        Instant cutoff = now.plusSeconds(91);
        v1.publish(v1At);
        v2.publish(v2At);
        v3.publish(v3At);
        FinancialTransaction transaction = transactions.saveAndFlush(
                new FinancialTransaction(UUID.randomUUID(), TransactionType.ACCOUNT_TRANSFER,
                        new BigDecimal("1000"), "KRW", cutoff,
                        "scn003_customer", "scn003_sender", "scn003_recipient",
                        TransactionChannel.MOBILE_BANKING, "scn003_device"));
        ExternalRiskSnapshot snapshot = new ExternalRiskSnapshot(
                transaction.getTransactionId(), cutoff, cutoff,
                "PROVIDER_V1", cutoff, ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.MATCHED,
                List.of(new ExternalRiskMatch(ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT)));
        if (mlEnabled) {
            when(mlService.cutoffFor(transaction.getTransactionId())).thenReturn(cutoff);
            when(mlService.appliesAt(cutoff)).thenReturn(true);
            when(mlService.infer(transaction.getTransactionId(), cutoff,
                    MlDetectionPolicy.SCN003_POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .thenReturn(new MlDetectionService.MlResult(10000, "ML_RISK_SIGNAL"));
        }
        when(ruleClient.analyzeV2(any(), eq(TRACE_ID))).thenAnswer(call ->
                response(call.getArgument(0, RuleAnalysisRequestV2.class)));

        var completed = orchestration.analyzeV2(transaction.getTransactionId(), snapshot, TRACE_ID);
        assertThat(completed.riskScore()).isEqualTo(mlEnabled ? 80 : 40);
        assertThat(completed.riskLevel()).isEqualTo(
                mlEnabled ? RiskLevel.CRITICAL : RiskLevel.MEDIUM);
        var rows = evidence.findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                completed.detectionResultId());
        if (mlEnabled) {
            assertThat(rows).extracting(row -> row.getEvidenceType())
                    .containsExactly(DetectionEvidenceType.RULE,
                            DetectionEvidenceType.EXTERNAL_RISK,
                            DetectionEvidenceType.BEHAVIOR_PATTERN,
                            DetectionEvidenceType.ML);
        } else {
            assertThat(rows).extracting(row -> row.getEvidenceType())
                    .containsExactly(DetectionEvidenceType.RULE,
                            DetectionEvidenceType.EXTERNAL_RISK,
                            DetectionEvidenceType.BEHAVIOR_PATTERN);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM detection_evidence evidence
                JOIN detection_result result ON result.id = evidence.detection_result_id
                WHERE result.detection_result_id = ?
                """, Long.class, completed.detectionResultId())).isEqualTo(mlEnabled ? 4L : 3L);
        assertThat(rows.get(1).getObservationSummary().toString())
                .doesNotContain("scn003_recipient", "scn003_sender", "scn003_customer");
        var view = adopted.find(transaction.getTransactionId().toString());
        assertThat(view.adoptedResult().scn003Evidence().recipientAccountMatched()).isTrue();
        assertThat(view.adoptedResult().scn003Evidence()
                .priorApprovedRecipientTransferObserved()).isFalse();
        assertThat(finalization.finalizeRiskResponse(transaction.getTransactionId())
                .processingStatus()).isEqualTo(
                        mlEnabled ? TransactionProcessingStatus.HELD
                                : TransactionProcessingStatus.APPROVED);
    }

    @Test
    void staleRecipientSnapshotFailsBeforeCreatingDetection() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.plusSeconds(91);
        v1.publish(now.plusSeconds(30));
        v2.publish(now.plusSeconds(60));
        v3.publish(now.plusSeconds(90));
        FinancialTransaction transaction = transactions.saveAndFlush(
                new FinancialTransaction(UUID.randomUUID(), TransactionType.ACCOUNT_TRANSFER,
                        new BigDecimal("1000"), "KRW", cutoff,
                        "scn003_customer", "scn003_sender", "scn003_recipient",
                        TransactionChannel.MOBILE_BANKING, "scn003_device"));
        ExternalRiskSnapshot stale = new ExternalRiskSnapshot(transaction.getTransactionId(),
                cutoff, cutoff, "PROVIDER_V1", cutoff.minusSeconds(86_400).minus(1, ChronoUnit.MICROS),
                ExternalRiskLookupStatus.SUCCEEDED, ExternalRiskPolicyResult.MATCHED,
                List.of(new ExternalRiskMatch(ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT)));

        assertThatThrownBy(() -> orchestration.analyzeV2(
                transaction.getTransactionId(), stale, TRACE_ID))
                .isInstanceOf(ExternalRiskLookupException.class)
                .satisfies(error -> assertThat(((ExternalRiskLookupException) error).category())
                        .isEqualTo(ExternalRiskFailureCategory.INVALID_RESPONSE));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM detection_result WHERE financial_transaction_id = ?",
                Long.class, transaction.getId())).isZero();
    }

    private RuleAnalysisResponse response(RuleAnalysisRequestV2 request) {
        var r005 = request.ruleVersions().stream().filter(item ->
                item.ruleCode().equals("EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT"))
                .findFirst().orElseThrow();
        var contributions = request.ruleVersions().stream().map(item -> {
            int index = request.ruleVersions().indexOf(item) + 1;
            return new RuleContributionResponse(RuleId.valueOf("R00" + index), index,
                    index == 5, index == 5 ? 40 : 0);
        }).toList();
        var summaries = List.of(
                new RuleScoreGroupSummaryResponse(RuleScoreGroupId.amount, 0, 15, 0, 0),
                new RuleScoreGroupSummaryResponse(RuleScoreGroupId.security, 0, 60, 0, 0),
                new RuleScoreGroupSummaryResponse(RuleScoreGroupId.beneficiary, 0, 10, 0, 0),
                new RuleScoreGroupSummaryResponse(RuleScoreGroupId.external_recipient, 40, 40, 40, 0));
        var score = new RuleScoringResultResponse("scoring-policy-v3", 40,
                RuleRiskLevel.MEDIUM, contributions, summaries);
        var observation = JsonNodeFactory.instance.objectNode()
                .put("providerCode", request.externalRisk().providerCode())
                .put("providerAsOf", request.externalRisk().providerAsOf().toString())
                .put("lookedUpAt", request.externalRisk().lookedUpAt().toString())
                .put("freshnessSeconds", 0);
        var matched = new RuleEvidenceResponse(RuleId.R005, r005.ruleVersionId(),
                r005.ruleCode(), "1", r005.reasonCode(), 5, 40,
                observation, request.externalRisk().providerAsOf());
        var identities = request.ruleVersions().stream().map(item ->
                new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                        item.fraudRuleId(), item.ruleVersionId(), item.ruleCode(),
                        item.versionNumber())).toList();
        String setVersion = new CanonicalRuleSetVersionCalculator().calculate(identities);
        return new RuleAnalysisResponse(request.transaction().transactionId(), TRACE_ID,
                new RuleAnalysisResultResponse(request.evaluationCutoffAt(),
                        setVersion, score, List.of(matched)));
    }
}
