package com.aifds.backend.scenario;

import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV4;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository.Window;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Backend reconstruction of the entire future v5 response, including both windows. */
public final class ScenarioAggregationRuleResponseValidator {
    private ScenarioAggregationRuleResponseValidator() { }

    public static void validate(RuleAnalysisRequestV4 request, RuleAnalysisResponse response) {
        ScenarioAggregationRuleVersionContract.validate(
                request.ruleVersions(), request.evaluationCutoffAt());
        require(response != null
                && request.transaction().transactionId().equals(response.transactionId()),
                "Rule v5 transaction mismatch");
        var analysis = response.analysis();
        require(request.evaluationCutoffAt().equals(analysis.evaluationCutoffAt())
                && new CanonicalRuleSetVersionCalculator().calculateV5(request.ruleVersions())
                        .equals(analysis.ruleSetVersion()), "Rule v5 cutoff/set mismatch");
        boolean recipientMatched = request.externalRisk().matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT
                && match.riskType() == ExternalRiskType.SUSPICIOUS_ACCOUNT
                && match.reasonCode() == ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT);
        var base = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), recipientMatched);
        var expected = ScenarioAggregationRulePolicy.evaluate(base, request.aggregationFacts());
        require(expected.equals(analysis.scoringResult()), "Rule v5 score attribution mismatch");
        int expectedCount = (int) expected.ruleContributions().stream()
                .filter(RuleContributionResponse::matched).count();
        require(analysis.evidence().size() == expectedCount, "Rule v5 Evidence count mismatch");
        int position = 0;
        for (int i = 0; i < 12; i++) {
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
                    "Rule v5 Evidence identity/contribution mismatch");
            JsonNode observation = i < 10
                    ? ScenarioRuleEvidenceContract.observation(i, request.base())
                    : observation(request, i);
            require(ScenarioRuleResponseValidator.sameObservation(
                            observation, evidence.observationSummary())
                    && (i < 10 ? ScenarioRuleEvidenceContract.occurredAt(i, request.base())
                            : request.evaluationCutoffAt()).equals(evidence.evidenceOccurredAt()),
                    "Rule v5 Evidence observation/time mismatch");
        }
    }

    public static JsonNode observation(RuleAnalysisRequestV4 request, int index) {
        if (index != 10 && index != 11) {
            throw new IllegalArgumentException("Unsupported aggregation Evidence index");
        }
        Window window = index == 10 ? request.aggregationFacts().outbound()
                : request.aggregationFacts().inbound();
        Instant t = request.evaluationCutoffAt();
        Instant from = t.minus(index == 10 ? Duration.ofMinutes(10) : Duration.ofHours(24));
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        return node.put("sourceVersion", request.aggregationFacts().sourceVersion())
                .put("mockSourceCode", "LOCAL_FDS_MOCK")
                .put("mockSourceVersion", "v1")
                .put("windowStartAt", wire(from))
                .put("evaluationCutoffAt", wire(t))
                .put("transferCount", window.transferCount())
                .put("distinctCount", window.distinctCount())
                .put("totalAmount", canonical(window.totalAmount()))
                .put("unknownCount", window.unknownCount())
                .put("coverageFactId", window.coverageFactId().toString());
    }

    private static String canonical(BigDecimal value) {
        try {
            return value.toBigIntegerExact().toString();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Non-integral aggregate KRW", exception);
        }
    }

    private static String wire(Instant value) {
        String whole = value.truncatedTo(ChronoUnit.SECONDS).toString();
        if (value.getNano() == 0) return whole;
        return whole.substring(0, whole.length() - 1)
                + String.format(Locale.ROOT, ".%06dZ", value.getNano() / 1_000);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
