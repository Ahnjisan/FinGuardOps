package com.aifds.backend.persistence;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
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
class Scn006007DetectionIntegrationTest extends PostgresqlIntegrationTestSupport {
    private static final String TRACE = "trace_scn006007_integration_0001";
    @Autowired RuleV1DefaultRuleSetPublicationService v1;
    @Autowired RuleV2LocalPublicationService v2;
    @Autowired RuleV3LocalPublicationService v3;
    @Autowired RuleV4LocalPublicationService v4;
    @Autowired RuleV5LocalPublicationService v5;
    @Autowired RuleV6LocalPublicationService v6;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired BehaviorEventRepository behaviors;
    @Autowired RuleAnalysisOrchestrationService orchestration;
    @Autowired AdoptedDetectionResultQueryService adopted;
    @Autowired RiskResponseFinalizationService finalization;
    @Autowired MockFactIntakeService mockFacts;
    @Autowired MockFundLinkIntakeService fundLinks;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RuleAnalysisHttpClient ruleClient;
    @MockitoBean MlDetectionService mlService;

    @Test
    void verifiedSourceToUseFlowsThroughPinnedRuleMlEvidenceAndCases() throws Exception {
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
        Instant cutoff = now.plus(Duration.ofMinutes(3));

        FinancialTransaction incoming = save(TransactionType.ACCOUNT_TRANSFER,
                "payer", "payer-account", "owner-account", "10000000",
                cutoff.minus(Duration.ofMinutes(20)));
        approve(incoming);
        FinancialTransaction unrelated = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "other-account", null, "9000000", cutoff.minusSeconds(30));
        approve(unrelated);
        FinancialTransaction atm = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "8000000", cutoff);
        coverage("owner", "owner-account", cutoff.minus(Duration.ofMinutes(30)), cutoff);
        link("SCN006", incoming, atm, "owner", "owner-account");

        FinancialTransaction loan = save(TransactionType.LOAN_DISBURSED,
                "borrower", "loan-account", null, "10000000",
                cutoff.minus(Duration.ofMinutes(40)));
        approve(loan);
        FinancialTransaction transfer = save(TransactionType.ACCOUNT_TRANSFER,
                "borrower", "loan-account", "new-recipient", "8000000", cutoff.plusSeconds(1));
        coverage("borrower", "loan-account", cutoff.minus(Duration.ofMinutes(60)),
                cutoff.plusSeconds(1));
        behaviors.saveAndFlush(new BehaviorEvent(UUID.randomUUID(),
                BehaviorEventType.BENEFICIARY_REGISTERED,
                cutoff.minus(Duration.ofMinutes(10)), "borrower", "loan-account",
                null, "new-recipient", null, "0".repeat(64)));
        link("SCN007", loan, transfer, "borrower", "loan-account");

        when(mlService.cutoffFor(any())).thenAnswer(call -> transactions
                .findByTransactionId(call.getArgument(0, UUID.class)).orElseThrow().getOccurredAt());
        when(mlService.appliesAt(any())).thenReturn(true);
        when(mlService.infer(any(), any(), eq(MlDetectionPolicy.SCN006_007_POLICY_VERSION),
                eq(MlDetectionPolicy.SCN006_007_MODEL_VERSION), any(), any(), any()))
                .thenReturn(new MlDetectionService.MlResult(0, "ML_BELOW_THRESHOLD"));
        when(ruleClient.analyzeV5(any(), eq(TRACE))).thenAnswer(call ->
                response(call.getArgument(0, RuleAnalysisRequestV5.class)));

        var atmResult = orchestration.analyzeV2(atm.getTransactionId(), snapshot(atm), TRACE);
        assertThat(atmResult.riskLevel().name()).isEqualTo("HIGH");
        var atmView = adopted.find(atm.getTransactionId().toString()).adoptedResult();
        assertThat(atmView.fundFlowEvidence().path("atm").path("ratioBasisPoints").asInt())
                .isEqualTo(8000);
        assertThat(finalization.finalizeRiskResponse(atm.getTransactionId())
                .processingStatus()).isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(caseLinks("SCN006")).isEqualTo(2L);

        var loanResult = orchestration.analyzeV2(transfer.getTransactionId(),
                snapshot(transfer), TRACE);
        assertThat(loanResult.riskLevel().name()).isEqualTo("HIGH");
        var loanView = adopted.find(transfer.getTransactionId().toString()).adoptedResult();
        assertThat(loanView.fundFlowEvidence().path("loan").path("ratioBasisPoints").asInt())
                .isEqualTo(8000);
        assertThat(finalization.finalizeRiskResponse(transfer.getTransactionId())
                .processingStatus()).isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(caseLinks("SCN007")).isEqualTo(2L);
    }

    private RuleAnalysisResponse response(RuleAnalysisRequestV5 request) {
        var base = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), false);
        var aggregation = ScenarioAggregationRulePolicy.evaluate(base, request.aggregationFacts());
        var score = ScenarioFundFlowRulePolicy.evaluate(aggregation, request.fundFlowFacts());
        var rows = new ArrayList<RuleEvidenceResponse>();
        for (int i = 0; i < 14; i++) {
            var contribution = score.ruleContributions().get(i);
            if (!contribution.matched()) continue;
            var version = request.ruleVersions().get(i);
            rows.add(new RuleEvidenceResponse(contribution.ruleId(), version.ruleVersionId(),
                    version.ruleCode(), Integer.toString(version.versionNumber()),
                    version.reasonCode(), i + 1, contribution.originalContribution(),
                    i < 10 ? ScenarioRuleEvidenceContract.observation(i, request.base().base())
                            : i < 12 ? ScenarioAggregationRuleResponseValidator.observation(
                                    request.base(), i)
                            : ScenarioFundFlowRuleResponseValidator.observation(request, i),
                    i < 10 ? ScenarioRuleEvidenceContract.occurredAt(i, request.base().base())
                            : request.evaluationCutoffAt(), contribution.appliedContribution()));
        }
        return new RuleAnalysisResponse(request.transaction().transactionId(), TRACE,
                new RuleAnalysisResultResponse(request.evaluationCutoffAt(),
                        new CanonicalRuleSetVersionCalculator().calculateV6(
                                request.ruleVersions()), score, rows));
    }

    private FinancialTransaction save(TransactionType type, String customer,
            String sender, String recipient, String amount, Instant at) {
        TransactionChannel channel = switch (type) {
            case ATM_WITHDRAWAL -> TransactionChannel.ATM;
            case LOAN_DISBURSED -> TransactionChannel.CORE_BANKING;
            case ACCOUNT_TRANSFER -> TransactionChannel.MOBILE_BANKING;
            case OPEN_BANKING_TRANSFER -> TransactionChannel.OPEN_BANKING;
        };
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(), type,
                new BigDecimal(amount), "KRW", at, customer, sender, recipient, channel, null));
    }

    private void approve(FinancialTransaction tx) {
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                tx.getId());
    }

    private void coverage(String customer, String account, Instant from, Instant through) {
        mockFacts.ingest(new MockFactRequest(UUID.randomUUID(),
                MockFactType.ACCOUNT_FLOW_HISTORY_COMPLETE, customer, account,
                null, null, null, through.minusSeconds(5), from, through,
                "COMPLETE", null, null, null, null));
    }

    private void link(String scenario, FinancialTransaction source,
            FinancialTransaction target, String customer, String account) {
        fundLinks.ingest(new MockFundLinkRequest(UUID.randomUUID(), scenario,
                source.getTransactionId(), target.getTransactionId(), customer,
                account, "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private ExternalRiskSnapshot snapshot(FinancialTransaction tx) {
        Instant t = tx.getOccurredAt();
        return new ExternalRiskSnapshot(tx.getTransactionId(), t, t, "PROVIDER_V1", t,
                ExternalRiskLookupStatus.SUCCEEDED, ExternalRiskPolicyResult.UNMATCHED,
                List.of());
    }

    private long caseLinks(String scenario) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM case_transaction link
                JOIN scenario_case_correlation cc ON cc.fraud_case_id = link.fraud_case_id
                WHERE cc.scenario_code = ?
                """, Long.class, scenario);
    }

    private void waitUntil(Instant at) throws InterruptedException {
        long millis = Duration.between(Instant.now(), at).toMillis() + 100;
        if (millis > 0) Thread.sleep(millis);
    }
}
