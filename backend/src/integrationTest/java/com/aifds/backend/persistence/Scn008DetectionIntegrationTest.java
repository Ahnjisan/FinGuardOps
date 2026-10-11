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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class Scn008DetectionIntegrationTest extends PostgresqlIntegrationTestSupport {
    private static final String TRACE = "trace_scn008_integration_0001";
    @Autowired RuleV1DefaultRuleSetPublicationService v1;
    @Autowired RuleV2LocalPublicationService v2;
    @Autowired RuleV3LocalPublicationService v3;
    @Autowired RuleV4LocalPublicationService v4;
    @Autowired RuleV5LocalPublicationService v5;
    @Autowired RuleV6LocalPublicationService v6;
    @Autowired RuleV7LocalPublicationService v7;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired RuleAnalysisOrchestrationService orchestration;
    @Autowired AdoptedDetectionResultQueryService adopted;
    @Autowired RiskResponseFinalizationService finalization;
    @Autowired MockFactIntakeService mockFacts;
    @Autowired MockFundLinkIntakeService fundLinks;
    @Autowired MockOpenAccountIntakeService ownership;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RuleAnalysisHttpClient ruleClient;
    @MockitoBean MlDetectionService mlService;

    @Test
    void verifiedChainFlowsThroughPinnedRuleMlEvidenceAndRepresentativeCase() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        v1.publish(now.plusSeconds(1));
        v2.publish(now.plusSeconds(2));
        v3.publish(now.plusSeconds(3));
        waitUntil(now.plusSeconds(3));
        v4.publish(now.plusSeconds(4));
        waitUntil(now.plusSeconds(4));
        v5.publish(now.plusSeconds(5));
        waitUntil(now.plusSeconds(5));
        v6.publish(now.plusSeconds(6));
        waitUntil(now.plusSeconds(6));
        v7.publish(now.plusSeconds(7));
        Instant cutoff = now.plus(Duration.ofMinutes(3));
        owner("external-a");
        owner("external-b");
        Thread.sleep(100);
        Instant sourceAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusMillis(10);
        FinancialTransaction a = save(TransactionType.OPEN_BANKING_TRANSFER,
                "external-a", "hub", "3000000", sourceAt);
        FinancialTransaction b = save(TransactionType.OPEN_BANKING_TRANSFER,
                "external-b", "hub", "7000000", sourceAt.plusMillis(1));
        approve(a);
        approve(b);
        FinancialTransaction unrelated = save(TransactionType.OPEN_BANKING_TRANSFER,
                "external-c", "hub", "9000000", sourceAt.plusMillis(2));
        approve(unrelated);
        FinancialTransaction target = save(TransactionType.ACCOUNT_TRANSFER,
                "hub", "outbound", "8000000", cutoff);
        mockFacts.ingest(new MockFactRequest(UUID.randomUUID(),
                MockFactType.ACCOUNT_FLOW_HISTORY_COMPLETE, "owner", "hub",
                null, null, null, Instant.now().truncatedTo(ChronoUnit.MICROS),
                cutoff.minus(Duration.ofMinutes(35)), cutoff,
                "COMPLETE", null, null, null, null));
        link(a, target);
        link(b, target);

        when(mlService.cutoffFor(any())).thenAnswer(call -> transactions
                .findByTransactionId(call.getArgument(0, UUID.class)).orElseThrow().getOccurredAt());
        when(mlService.appliesAt(any())).thenReturn(true);
        when(mlService.infer(any(), any(), eq(MlDetectionPolicy.SCN008_POLICY_VERSION),
                eq(MlDetectionPolicy.SCN008_MODEL_VERSION), any(), any(), any(), any()))
                .thenReturn(new MlDetectionService.MlResult(0, "ML_BELOW_THRESHOLD"));
        when(ruleClient.analyzeV6(any(), eq(TRACE))).thenAnswer(call ->
                response(call.getArgument(0, RuleAnalysisRequestV6.class)));

        var completed = orchestration.analyzeV2(target.getTransactionId(), snapshot(target), TRACE);
        assertThat(completed.riskLevel().name()).isEqualTo("HIGH");
        var view = adopted.find(target.getTransactionId().toString()).adoptedResult();
        assertThat(view.scoringPolicyVersion()).isEqualTo(MlDetectionPolicy.SCN008_POLICY_VERSION);
        assertThat(view.openBankingEvidence().path("ratioBasisPoints").asInt()).isEqualTo(8000);
        assertThat(view.openBankingEvidence().path("stages")).hasSize(2);
        assertThat(view.openBankingEvidence().toString()).doesNotContain("external-a", "external-b");
        assertThat(finalization.finalizeRiskResponse(target.getTransactionId())
                .processingStatus()).isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(caseLinks()).isEqualTo(3L);
        assertThat(caseContains(unrelated)).isFalse();
    }

    private RuleAnalysisResponse response(RuleAnalysisRequestV6 request) {
        var base = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), false);
        var aggregation = ScenarioAggregationRulePolicy.evaluate(base, request.aggregationFacts());
        var funding = ScenarioFundFlowRulePolicy.evaluate(aggregation, request.fundFlowFacts());
        var score = ScenarioOpenBankingRulePolicy.evaluate(funding, request.openBankingFacts());
        var rows = new ArrayList<RuleEvidenceResponse>();
        for (int i = 0; i < 15; i++) {
            var contribution = score.ruleContributions().get(i);
            if (!contribution.matched()) continue;
            var version = request.ruleVersions().get(i);
            rows.add(new RuleEvidenceResponse(contribution.ruleId(), version.ruleVersionId(),
                    version.ruleCode(), Integer.toString(version.versionNumber()),
                    version.reasonCode(), i + 1, contribution.originalContribution(),
                    i < 10 ? ScenarioRuleEvidenceContract.observation(i, request.base().base().base())
                            : i < 12 ? ScenarioAggregationRuleResponseValidator.observation(
                                    request.base().base(), i)
                            : i < 14 ? ScenarioFundFlowRuleResponseValidator.observation(
                                    request.base(), i)
                            : ScenarioOpenBankingRuleResponseValidator.observation(request),
                    i < 10 ? ScenarioRuleEvidenceContract.occurredAt(
                            i, request.base().base().base()) : request.evaluationCutoffAt(),
                    contribution.appliedContribution()));
        }
        return new RuleAnalysisResponse(request.transaction().transactionId(), TRACE,
                new RuleAnalysisResultResponse(request.evaluationCutoffAt(),
                        new CanonicalRuleSetVersionCalculator().calculateV7(
                                request.ruleVersions()), score, rows));
    }

    private FinancialTransaction save(TransactionType type, String sender,
            String recipient, String amount, Instant at) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(), type,
                new BigDecimal(amount), "KRW", at, "owner", sender, recipient,
                type == TransactionType.OPEN_BANKING_TRANSFER
                        ? TransactionChannel.OPEN_BANKING : TransactionChannel.MOBILE_BANKING,
                null));
    }

    private void approve(FinancialTransaction tx) {
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                tx.getId());
    }

    private void owner(String account) {
        ownership.ingest(new MockOpenAccountRequest(UUID.randomUUID(), "owner", account,
                "VERIFIED", "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private void link(FinancialTransaction source, FinancialTransaction target) {
        fundLinks.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN008",
                source.getTransactionId(), target.getTransactionId(), "owner", "hub",
                "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private ExternalRiskSnapshot snapshot(FinancialTransaction tx) {
        Instant t = tx.getOccurredAt();
        return new ExternalRiskSnapshot(tx.getTransactionId(), t, t, "PROVIDER_V1", t,
                ExternalRiskLookupStatus.SUCCEEDED, ExternalRiskPolicyResult.UNMATCHED,
                List.of());
    }

    private long caseLinks() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM case_transaction link
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = link.fraud_case_id
                WHERE cc.scenario_code = 'SCN008'
                """, Long.class);
    }

    private boolean caseContains(FinancialTransaction tx) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM case_transaction link
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = link.fraud_case_id
                WHERE cc.scenario_code = 'SCN008' AND link.financial_transaction_id = ?
                """, Long.class, tx.getId()) > 0;
    }

    private void waitUntil(Instant at) throws InterruptedException {
        long millis = Duration.between(Instant.now(), at).toMillis() + 100;
        if (millis > 0) Thread.sleep(millis);
    }
}
