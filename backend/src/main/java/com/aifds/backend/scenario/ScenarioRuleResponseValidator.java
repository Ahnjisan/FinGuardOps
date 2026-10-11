package com.aifds.backend.scenario;

import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV3;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Iterator;
import java.util.Map;

public final class ScenarioRuleResponseValidator {
    private ScenarioRuleResponseValidator() { }

    public static void validate(RuleAnalysisRequestV3 request, RuleAnalysisResponse response) {
        require("KRW".equals(request.transaction().currencyCode()),
                "Rule v4 requires KRW");
        ScenarioRuleVersionContract.validate(request.ruleVersions(), request.evaluationCutoffAt());
        require(response != null && request.transaction().transactionId().equals(response.transactionId()),
                "Rule v4 transaction mismatch");
        var analysis = response.analysis();
        require(analysis != null && request.evaluationCutoffAt().equals(analysis.evaluationCutoffAt()),
                "Rule v4 cutoff mismatch");
        String expectedSet = new CanonicalRuleSetVersionCalculator()
                .calculateV4(request.ruleVersions());
        require(expectedSet.equals(analysis.ruleSetVersion()), "Rule v4 set hash mismatch");
        boolean recipientMatched = request.externalRisk().matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT
                && match.riskType() == ExternalRiskType.SUSPICIOUS_ACCOUNT
                && match.reasonCode() == ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT);
        var expected = ScenarioRulePolicy.evaluate(new BigDecimal(request.transaction().amount()),
                request.evaluationCutoffAt(), request.transaction().transactionType(),
                request.scenarioFacts(), recipientMatched);
        require(expected.equals(analysis.scoringResult()), "Rule v4 score attribution mismatch");
        List<RuleEvidenceResponse> evidence = analysis.evidence();
        int expectedCount = (int) expected.ruleContributions().stream()
                .filter(RuleContributionResponse::matched).count();
        require(evidence.size() == expectedCount, "Rule v4 Evidence count mismatch");
        int evidenceIndex = 0;
        for (int i = 0; i < expected.ruleContributions().size(); i++) {
            RuleContributionResponse contribution = expected.ruleContributions().get(i);
            if (!contribution.matched()) continue;
            RuleEvidenceResponse item = evidence.get(evidenceIndex++);
            var version = request.ruleVersions().get(i);
            require(item.ruleId() == contribution.ruleId()
                    && item.executionOrder() == i + 1
                    && version.ruleVersionId().equals(item.ruleVersionId())
                    && version.ruleCode().equals(item.ruleCode())
                    && Integer.toString(version.versionNumber()).equals(item.ruleVersion())
                    && version.reasonCode().equals(item.reasonCode()),
                    "Rule v4 Evidence identity mismatch " + contribution.ruleId());
            require(item.scoreContribution() == contribution.originalContribution()
                    && contribution.appliedContribution().equals(item.appliedContribution()),
                    "Rule v4 Evidence contribution mismatch " + contribution.ruleId());
            require(sameObservation(ScenarioRuleEvidenceContract.observation(i, request),
                            item.observationSummary()),
                    "Rule v4 Evidence observation mismatch " + contribution.ruleId());
            require(ScenarioRuleEvidenceContract.occurredAt(i, request)
                            .equals(item.evidenceOccurredAt()),
                    "Rule v4 Evidence time mismatch " + contribution.ruleId());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static boolean sameObservation(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null || !expected.isObject() || !actual.isObject()
                || expected.size() != actual.size()) return false;
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode value = actual.get(field.getKey());
            if (value == null) return false;
            JsonNode source = field.getValue();
            if (source.isIntegralNumber() && value.isIntegralNumber()) {
                if (!source.bigIntegerValue().equals(value.bigIntegerValue())) return false;
            } else if (!source.equals(value)) return false;
        }
        return true;
    }
}
