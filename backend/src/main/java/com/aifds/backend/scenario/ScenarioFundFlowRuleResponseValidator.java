package com.aifds.backend.scenario;

import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV5;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Rebuild every Rule v6 contribution and Evidence field from pinned facts. */
public final class ScenarioFundFlowRuleResponseValidator {
    private ScenarioFundFlowRuleResponseValidator() { }

    public static void validate(RuleAnalysisRequestV5 request, RuleAnalysisResponse response) {
        ScenarioFundFlowRuleVersionContract.validate(
                request.ruleVersions(), request.evaluationCutoffAt());
        require(response != null
                && request.transaction().transactionId().equals(response.transactionId()),
                "Rule v6 transaction mismatch");
        var analysis = response.analysis();
        require(request.evaluationCutoffAt().equals(analysis.evaluationCutoffAt())
                && new CanonicalRuleSetVersionCalculator().calculateV6(request.ruleVersions())
                        .equals(analysis.ruleSetVersion()), "Rule v6 cutoff/set mismatch");
        boolean recipientMatched = request.externalRisk().matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT
                && match.riskType() == ExternalRiskType.SUSPICIOUS_ACCOUNT
                && match.reasonCode() == ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT);
        var base = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), recipientMatched);
        var aggregation = ScenarioAggregationRulePolicy.evaluate(base,
                request.aggregationFacts());
        var expected = ScenarioFundFlowRulePolicy.evaluate(aggregation,
                request.fundFlowFacts());
        require(expected.equals(analysis.scoringResult()),
                "Rule v6 score attribution mismatch");
        int expectedCount = (int) expected.ruleContributions().stream()
                .filter(com.aifds.backend.rule.client.dto.RuleContributionResponse::matched)
                .count();
        require(analysis.evidence().size() == expectedCount,
                "Rule v6 Evidence count mismatch");
        int position = 0;
        for (int i = 0; i < 14; i++) {
            var contribution = expected.ruleContributions().get(i);
            if (!contribution.matched()) continue;
            var evidence = analysis.evidence().get(position++);
            var version = request.ruleVersions().get(i);
            require(evidence.ruleId() == contribution.ruleId()
                    && evidence.executionOrder() == i + 1
                    && evidence.ruleVersionId().equals(version.ruleVersionId())
                    && evidence.ruleCode().equals(version.ruleCode())
                    && evidence.ruleVersion().equals(Integer.toString(version.versionNumber()))
                    && evidence.reasonCode().equals(version.reasonCode())
                    && evidence.scoreContribution() == contribution.originalContribution()
                    && contribution.appliedContribution().equals(evidence.appliedContribution()),
                    "Rule v6 Evidence identity/contribution mismatch");
            JsonNode observation = i < 10
                    ? ScenarioRuleEvidenceContract.observation(i, request.base().base())
                    : i < 12
                            ? ScenarioAggregationRuleResponseValidator.observation(
                                    request.base(), i)
                            : observation(request, i);
            require(ScenarioRuleResponseValidator.sameObservation(
                            observation, evidence.observationSummary())
                    && (i < 10 ? ScenarioRuleEvidenceContract.occurredAt(
                            i, request.base().base())
                            : request.evaluationCutoffAt())
                            .equals(evidence.evidenceOccurredAt()),
                    "Rule v6 Evidence observation/time mismatch");
        }
    }

    public static JsonNode observation(RuleAnalysisRequestV5 request, int index) {
        if (index != 12 && index != 13) {
            throw new IllegalArgumentException("Unsupported fund flow Evidence index");
        }
        var flow = index == 12 ? request.fundFlowFacts().atm()
                : request.fundFlowFacts().loan();
        Instant cutoff = request.evaluationCutoffAt();
        var node = JsonNodeFactory.instance.objectNode()
                .put("sourceVersion", request.fundFlowFacts().sourceVersion())
                .put("mockSourceCode", "LOCAL_FDS_MOCK")
                .put("mockSourceVersion", "v2")
                .put("evaluationCutoffAt", wire(cutoff))
                .put("sourceTransactionId", flow.sourceTransactionId().toString())
                .put("currentLinkId", flow.currentLinkId().toString())
                .put("historyCoverageFactId", flow.historyCoverageFactId().toString())
                .put("sourceOccurredAt", wire(flow.sourceOccurredAt()))
                .put("sourceApprovedAt", wire(flow.sourceApprovedAt()))
                .put("sourceAmount", canonical(flow.sourceAmount()))
                .put("linkedAmount", canonical(flow.linkedAmount()))
                .put("ratioBasisPoints", flow.ratioBasisPoints());
        if (flow.beneficiaryEventId() != null) {
            node.put("beneficiaryEventId", flow.beneficiaryEventId().toString());
        }
        var prior = node.putArray("linkedPriorTransactionIds");
        flow.linkedPriorTransactionIds().forEach(id -> prior.add(id.toString()));
        return node;
    }

    private static String canonical(BigDecimal value) {
        return value.toBigIntegerExact().toString();
    }

    private static String wire(Instant value) {
        String whole = value.truncatedTo(ChronoUnit.SECONDS).toString();
        if (value.getNano() == 0) return whole;
        return whole.substring(0, whole.length() - 1)
                + String.format(Locale.ROOT, ".%06dZ", value.getNano() / 1_000);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
