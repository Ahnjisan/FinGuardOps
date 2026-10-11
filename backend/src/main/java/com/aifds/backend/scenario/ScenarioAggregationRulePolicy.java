package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoreGroupId;
import com.aifds.backend.rule.client.dto.RuleScoreGroupSummaryResponse;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Independent Backend recomputation of the two additive synthetic Rule signals. */
public final class ScenarioAggregationRulePolicy {
    private static final int WEIGHT = 25;
    private static final int GROUP_CAP = 40;

    private ScenarioAggregationRulePolicy() { }

    public static RuleScoringResultResponse evaluate(RuleScoringResultResponse base,
                                                     ScenarioAggregationFacts facts) {
        RuleScoringResultResponse validated = Objects.requireNonNull(base);
        if (!"scoring-policy-v4".equals(validated.scoringPolicyVersion())
                || validated.ruleContributions().size() != 10
                || validated.groupSummaries().size() != 4) {
            throw new IllegalArgumentException("Invalid base Rule attribution");
        }
        boolean[] matched = {ScenarioAggregationPolicy.distributedOutbound(facts),
                ScenarioAggregationPolicy.manyCustomerInbound(facts)};
        boolean independent = validated.riskScore() >= 50
                || matched[0] && (validated.ruleContributions().get(3).matched()
                        || validated.ruleContributions().get(4).matched())
                || matched[1] && validated.ruleContributions().get(4).matched();
        int ceiling = independent || !(matched[0] || matched[1]) ? 100 : 49;
        List<RuleContributionResponse> contributions = new ArrayList<>(
                validated.ruleContributions());
        int raw = 0;
        int applied = 0;
        int score = validated.riskScore();
        for (int i = 0; i < matched.length; i++) {
            int original = matched[i] ? WEIGHT : 0;
            int accepted = Math.min(original,
                    Math.min(GROUP_CAP - applied, Math.max(0, ceiling - score)));
            raw += original;
            applied += accepted;
            score += accepted;
            contributions.add(new RuleContributionResponse(i == 0 ? RuleId.R011 : RuleId.R012,
                    i + 11, matched[i], original, accepted));
        }
        List<RuleScoreGroupSummaryResponse> groups = new ArrayList<>(
                validated.groupSummaries());
        groups.add(new RuleScoreGroupSummaryResponse(RuleScoreGroupId.flow_aggregation,
                raw, GROUP_CAP, applied, raw - applied));
        RuleRiskLevel level = score < 20 ? RuleRiskLevel.LOW
                : score < 50 ? RuleRiskLevel.MEDIUM
                : score < 80 ? RuleRiskLevel.HIGH : RuleRiskLevel.CRITICAL;
        return new RuleScoringResultResponse("scoring-policy-v5", score, level,
                List.copyOf(contributions), List.copyOf(groups));
    }

    public static int scoreCeiling(RuleScoringResultResponse score) {
        var rows = score.ruleContributions();
        if (rows.size() != 12) throw new IllegalArgumentException("Rule v5 needs 12 rows");
        boolean outbound = rows.get(10).matched();
        boolean inbound = rows.get(11).matched();
        int base = rows.subList(0, 10).stream()
                .mapToInt(RuleContributionResponse::appliedContribution).sum();
        return base >= 50 || outbound && (rows.get(3).matched() || rows.get(4).matched())
                || inbound && rows.get(4).matched() || !(outbound || inbound) ? 100 : 49;
    }
}
