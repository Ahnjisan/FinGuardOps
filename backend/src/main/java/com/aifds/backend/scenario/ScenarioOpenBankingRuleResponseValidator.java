package com.aifds.backend.scenario;

import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV6;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Rebuild all fifteen contributions and every rule Evidence from pinned facts. */
public final class ScenarioOpenBankingRuleResponseValidator {
    private ScenarioOpenBankingRuleResponseValidator() { }

    public static void validate(RuleAnalysisRequestV6 request, RuleAnalysisResponse response) {
        ScenarioOpenBankingRuleVersionContract.validate(
                request.ruleVersions(), request.evaluationCutoffAt());
        require(response != null
                && request.transaction().transactionId().equals(response.transactionId()),
                "Rule v7 transaction mismatch");
        var analysis = response.analysis();
        require(request.evaluationCutoffAt().equals(analysis.evaluationCutoffAt())
                && new CanonicalRuleSetVersionCalculator().calculateV7(request.ruleVersions())
                        .equals(analysis.ruleSetVersion()), "Rule v7 cutoff/set mismatch");
        boolean recipientMatched = request.externalRisk().matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT
                && match.riskType() == ExternalRiskType.SUSPICIOUS_ACCOUNT
                && match.reasonCode() == ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT);
        var base = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), recipientMatched);
        var aggregation = ScenarioAggregationRulePolicy.evaluate(base,
                request.aggregationFacts());
        var funding = ScenarioFundFlowRulePolicy.evaluate(aggregation,
                request.fundFlowFacts());
        var expected = ScenarioOpenBankingRulePolicy.evaluate(funding,
                request.openBankingFacts());
        require(expected.equals(analysis.scoringResult()),
                "Rule v7 score attribution mismatch");
        int expectedCount = (int) expected.ruleContributions().stream()
                .filter(com.aifds.backend.rule.client.dto.RuleContributionResponse::matched)
                .count();
        require(analysis.evidence().size() == expectedCount,
                "Rule v7 Evidence count mismatch");
        int position = 0;
        for (int i = 0; i < 15; i++) {
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
                    "Rule v7 Evidence identity/contribution mismatch");
            JsonNode observation = i < 10
                    ? ScenarioRuleEvidenceContract.observation(i, request.base().base().base())
                    : i < 12 ? ScenarioAggregationRuleResponseValidator.observation(
                            request.base().base(), i)
                    : i < 14 ? ScenarioFundFlowRuleResponseValidator.observation(
                            request.base(), i)
                    : observation(request);
            require(ScenarioRuleResponseValidator.sameObservation(
                            observation, evidence.observationSummary())
                    && (i < 10 ? ScenarioRuleEvidenceContract.occurredAt(
                            i, request.base().base().base())
                            : request.evaluationCutoffAt())
                            .equals(evidence.evidenceOccurredAt()),
                    "Rule v7 Evidence observation/time mismatch");
        }
    }

    public static JsonNode observation(RuleAnalysisRequestV6 request) {
        var facts = request.openBankingFacts();
        var node = JsonNodeFactory.instance.objectNode()
                .put("sourceVersion", facts.sourceVersion())
                .put("mockSourceCode", "LOCAL_OPEN_BANKING_MOCK")
                .put("mockSourceVersion", "v1")
                .put("evaluationCutoffAt", wire(request.evaluationCutoffAt()))
                .put("historyCoverageFactId", facts.historyCoverageFactId().toString())
                .put("concentrationStartedAt", wire(facts.concentrationStartedAt()))
                .put("concentrationCompletedAt", wire(facts.concentrationCompletedAt()))
                .put("concentrationAmount", canonical(facts.concentrationAmount()))
                .put("resendAmount", canonical(facts.resendAmount()))
                .put("ratioBasisPoints", facts.ratioBasisPoints());
        var stages = node.putArray("stages");
        for (var stage : facts.stages()) {
            stages.addObject()
                    .put("sourceTransactionId", stage.sourceTransactionId().toString())
                    .put("ownershipAssertionId", stage.ownershipAssertionId().toString())
                    .put("linkId", stage.linkId().toString())
                    .put("occurredAt", wire(stage.occurredAt()))
                    .put("approvedAt", wire(stage.approvedAt()))
                    .put("amount", canonical(stage.amount()));
        }
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
