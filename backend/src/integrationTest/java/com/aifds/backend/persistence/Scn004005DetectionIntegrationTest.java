package com.aifds.backend.persistence;

import com.aifds.backend.detection.ml.MlDetectionPolicy;
import com.aifds.backend.detection.ml.MlDetectionService;
import com.aifds.backend.detection.service.AdoptedDetectionResultQueryService;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.domain.*;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.*;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.service.*;
import com.aifds.backend.scenario.*;
import com.aifds.backend.scenario.mock.*;
import com.aifds.backend.transaction.entity.*;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Scn004005DetectionIntegrationTest extends PostgresqlIntegrationTestSupport {
    private static final String TRACE = "trace_scn004005_integration_0001";
    @Autowired RuleV1DefaultRuleSetPublicationService v1;
    @Autowired RuleV2LocalPublicationService v2;
    @Autowired RuleV3LocalPublicationService v3;
    @Autowired RuleV4LocalPublicationService v4;
    @Autowired RuleV5LocalPublicationService v5;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired RuleAnalysisOrchestrationService orchestration;
    @Autowired AdoptedDetectionResultQueryService adopted;
    @Autowired RiskResponseFinalizationService finalization;
    @Autowired MockFactIntakeService mockFacts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RuleAnalysisHttpClient ruleClient;
    @MockitoBean MlDetectionService mlService;

    @Test
    void cutoffWindowsFlowThroughPinnedRuleMlEvidenceAndBusinessResponse() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t1 = now.plusSeconds(2);
        Instant t2 = now.plusSeconds(3);
        Instant t3 = now.plusSeconds(4);
        Instant t4 = now.plusSeconds(5);
        Instant t5 = now.plusSeconds(6);
        v1.publish(t1);
        v2.publish(t2);
        v3.publish(t3);
        waitUntil(t3);
        v4.publish(t4);
        waitUntil(t4);
        v5.publish(t5);
        Instant cutoff = now.plus(Duration.ofMinutes(3));

        approve(save("out-customer", "out-sender", "recipient-one",
                cutoff.minusSeconds(120), "1000000"));
        approve(save("out-customer", "out-sender", "recipient-two",
                cutoff.minusSeconds(60), "1100000"));
        FinancialTransaction outbound = save("out-customer", "out-sender",
                "recipient-three", cutoff, "1200000");
        cover(MockFactType.TRANSFER_HISTORY_COMPLETE, "out-customer", null,
                cutoff.minus(Duration.ofMinutes(10)), cutoff.plusSeconds(2));
        FinancialTransaction outboundHigh = save("out-customer", "out-sender",
                "recipient-four", cutoff.plusSeconds(2), "1000000");

        approve(save("in-a", "in-a-sender", "collector", cutoff.minusSeconds(120),
                "2000000"));
        approve(save("in-b", "in-b-sender", "collector", cutoff.minusSeconds(60),
                "2100000"));
        FinancialTransaction inbound = save("in-c", "in-c-sender", "collector",
                cutoff.plusSeconds(1), "1000000");
        cover(MockFactType.INBOUND_ACCOUNT_HISTORY_COMPLETE, "collector-owner", "collector",
                cutoff.minus(Duration.ofHours(24)), cutoff.plusSeconds(1));

        when(mlService.cutoffFor(any())).thenAnswer(call -> transactions
                .findByTransactionId(call.getArgument(0, UUID.class)).orElseThrow().getOccurredAt());
        when(mlService.appliesAt(any())).thenReturn(true);
        when(mlService.infer(any(), any(), eq(MlDetectionPolicy.SCN004_005_POLICY_VERSION),
                eq(MlDetectionPolicy.SCN004_005_MODEL_VERSION), any(), any()))
                .thenAnswer(call -> new MlDetectionService.MlResult(
                        outbound.getTransactionId().equals(call.getArgument(0)) ? 9000 : 0,
                        outbound.getTransactionId().equals(call.getArgument(0))
                                ? "ML_RISK_SIGNAL" : "ML_BELOW_THRESHOLD"));
        when(ruleClient.analyzeV4(any(), eq(TRACE))).thenAnswer(call ->
                response(call.getArgument(0, RuleAnalysisRequestV4.class)));

        var out = orchestration.analyzeV2(outbound.getTransactionId(),
                snapshot(outbound, false), TRACE);
        assertThat(out.riskScore()).isEqualTo(49);
        assertThat(out.riskLevel().name()).isEqualTo("MEDIUM");
        var outView = adopted.find(outbound.getTransactionId().toString()).adoptedResult();
        assertThat(outView.aggregationEvidence().path("outbound").path("status").asText())
                .isEqualTo("AVAILABLE");
        assertThat(outView.aggregationEvidence().path("outbound").path("transferCount").asInt())
                .isEqualTo(3);
        assertThat(finalization.finalizeRiskResponse(outbound.getTransactionId())
                .processingStatus()).isEqualTo(TransactionProcessingStatus.APPROVED);

        var outHigh = orchestration.analyzeV2(outboundHigh.getTransactionId(),
                snapshot(outboundHigh, true), TRACE);
        assertThat(outHigh.riskScore()).isGreaterThanOrEqualTo(50);
        assertThat(finalization.finalizeRiskResponse(outboundHigh.getTransactionId())
                .processingStatus()).isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM case_transaction link
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = link.fraud_case_id
                WHERE cc.scenario_code = 'SCN004'
                """, Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log log
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = (
                    SELECT fc.id FROM fraud_case fc WHERE fc.case_id = log.case_id)
                WHERE cc.scenario_code = 'SCN004'
                  AND log.action = 'CASE_TRANSACTION_LINKED'
                """, Long.class)).isEqualTo(4L);

        var in = orchestration.analyzeV2(inbound.getTransactionId(),
                snapshot(inbound, true), TRACE);
        assertThat(in.riskScore()).isGreaterThanOrEqualTo(50);
        var inView = adopted.find(inbound.getTransactionId().toString()).adoptedResult();
        assertThat(inView.aggregationEvidence().path("inbound").path("distinctCount").asInt())
                .isEqualTo(3);
        assertThat(finalization.finalizeRiskResponse(inbound.getTransactionId())
                .processingStatus()).isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scenario_case_correlation "
                + "WHERE scenario_code = 'SCN005'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM case_transaction link
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = link.fraud_case_id
                WHERE cc.scenario_code = 'SCN005'
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log log
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = (
                    SELECT fc.id FROM fraud_case fc WHERE fc.case_id = log.case_id)
                WHERE cc.scenario_code = 'SCN005'
                  AND log.action = 'CASE_TRANSACTION_LINKED'
                """, Long.class)).isEqualTo(3L);
    }

    private RuleAnalysisResponse response(RuleAnalysisRequestV4 request) {
        boolean risky = request.externalRisk().matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT);
        var base = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), risky);
        var score = ScenarioAggregationRulePolicy.evaluate(base, request.aggregationFacts());
        var rows = new ArrayList<RuleEvidenceResponse>();
        for (int i = 0; i < 12; i++) {
            var contribution = score.ruleContributions().get(i);
            if (!contribution.matched()) continue;
            var version = request.ruleVersions().get(i);
            rows.add(new RuleEvidenceResponse(contribution.ruleId(), version.ruleVersionId(),
                    version.ruleCode(), Integer.toString(version.versionNumber()),
                    version.reasonCode(), i + 1, contribution.originalContribution(),
                    i < 10 ? ScenarioRuleEvidenceContract.observation(i, request.base())
                            : ScenarioAggregationRuleResponseValidator.observation(request, i),
                    i < 10 ? ScenarioRuleEvidenceContract.occurredAt(i, request.base())
                            : request.evaluationCutoffAt(), contribution.appliedContribution()));
        }
        return new RuleAnalysisResponse(request.transaction().transactionId(), TRACE,
                new RuleAnalysisResultResponse(request.evaluationCutoffAt(),
                        new CanonicalRuleSetVersionCalculator().calculateV5(
                                request.ruleVersions()), score, rows));
    }

    private FinancialTransaction save(String customer, String sender, String recipient,
                                      Instant at, String amount) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER, new BigDecimal(amount), "KRW", at,
                customer, sender, recipient, TransactionChannel.MOBILE_BANKING, null));
    }

    private void approve(FinancialTransaction transaction) {
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                transaction.getId());
    }

    private void cover(MockFactType type, String owner, String account,
                       Instant from, Instant through) {
        mockFacts.ingest(new MockFactRequest(UUID.randomUUID(), type, owner, account,
                null, null, null, through.minusSeconds(30), from, through,
                "COMPLETE", null, null, null, null));
    }

    private ExternalRiskSnapshot snapshot(FinancialTransaction tx, boolean risky) {
        Instant t = tx.getOccurredAt();
        var matches = risky ? List.of(new ExternalRiskMatch(
                ExternalRiskSubjectType.RECIPIENT_ACCOUNT, ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT)) : List.<ExternalRiskMatch>of();
        return new ExternalRiskSnapshot(tx.getTransactionId(), t, t, "PROVIDER_V1", t,
                ExternalRiskLookupStatus.SUCCEEDED,
                risky ? ExternalRiskPolicyResult.MATCHED : ExternalRiskPolicyResult.UNMATCHED,
                matches);
    }

    private void waitUntil(Instant cutoff) throws InterruptedException {
        long millis = Duration.between(Instant.now(), cutoff).toMillis() + 100;
        if (millis > 0) Thread.sleep(millis);
    }
}
