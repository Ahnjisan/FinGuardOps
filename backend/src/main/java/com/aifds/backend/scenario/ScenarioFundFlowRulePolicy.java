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

/** Independent Backend recomputation of the approved local source-to-use rules. */
public final class ScenarioFundFlowRulePolicy {
    private static final int WEIGHT = 50;

    private ScenarioFundFlowRulePolicy() { }

    public static RuleScoringResultResponse evaluate(RuleScoringResultResponse base,
                                                      ScenarioFundFlowFacts facts) {
        RuleScoringResultResponse validated = Objects.requireNonNull(base);
        if (!"scoring-policy-v5".equals(validated.scoringPolicyVersion())
                || validated.ruleContributions().size() != 12
                || validated.groupSummaries().size() != 5) {
            throw new IllegalArgumentException("Invalid Rule v5 attribution");
        }
        boolean[] hits = {facts.atm().hit(), facts.loan().hit()};
        boolean independent = validated.ruleContributions().get(1).matched()
                || validated.ruleContributions().get(2).matched()
                || validated.ruleContributions().get(4).matched()
                || validated.ruleContributions().get(6).matched()
                || validated.ruleContributions().get(7).matched()
                || validated.ruleContributions().get(8).matched()
                || validated.ruleContributions().get(9).matched();
        int ceiling = independent || !hits[0] && !hits[1]
                ? 100 : Math.max(validated.riskScore(), 79);
        List<RuleContributionResponse> rows = new ArrayList<>(
                validated.ruleContributions());
        int raw = 0;
        int applied = 0;
        int score = validated.riskScore();
        for (int i = 0; i < hits.length; i++) {
            int original = hits[i] ? WEIGHT : 0;
            int accepted = Math.min(original,
                    Math.min(WEIGHT - applied, Math.max(0, ceiling - score)));
            raw += original;
            applied += accepted;
            score += accepted;
            rows.add(new RuleContributionResponse(i == 0 ? RuleId.R013 : RuleId.R014,
                    i + 13, hits[i], original, accepted));
        }
        List<RuleScoreGroupSummaryResponse> groups = new ArrayList<>(
                validated.groupSummaries());
        groups.add(new RuleScoreGroupSummaryResponse(RuleScoreGroupId.flow_link,
                raw, WEIGHT, applied, raw - applied));
        RuleRiskLevel level = score < 20 ? RuleRiskLevel.LOW
                : score < 50 ? RuleRiskLevel.MEDIUM
                : score < 80 ? RuleRiskLevel.HIGH : RuleRiskLevel.CRITICAL;
        return new RuleScoringResultResponse("scoring-policy-v6", score, level,
                List.copyOf(rows), List.copyOf(groups));
    }

    public static int scoreCeiling(RuleScoringResultResponse score) {
        var rows = score.ruleContributions();
        if (rows.size() != 14) throw new IllegalArgumentException("Rule v6 needs 14 rows");
        boolean flow = rows.get(12).matched() || rows.get(13).matched();
        boolean independent = rows.get(1).matched() || rows.get(2).matched()
                || rows.get(4).matched() || rows.get(6).matched()
                || rows.get(7).matched() || rows.get(8).matched()
                || rows.get(9).matched();
        return !flow || independent ? 100 : Math.max(score.riskScore(), 79);
    }
}
