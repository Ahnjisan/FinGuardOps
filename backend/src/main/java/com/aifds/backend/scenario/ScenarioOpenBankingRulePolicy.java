package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoreGroupId;
import com.aifds.backend.rule.client.dto.RuleScoreGroupSummaryResponse;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;

import java.util.ArrayList;
import java.util.List;

/** Independent Backend recomputation of the approved SCN-008 local Rule. */
public final class ScenarioOpenBankingRulePolicy {
    private ScenarioOpenBankingRulePolicy() { }

    public static RuleScoringResultResponse evaluate(RuleScoringResultResponse base,
                                                      ScenarioOpenBankingFacts facts) {
        if (!"scoring-policy-v6".equals(base.scoringPolicyVersion())
                || base.ruleContributions().size() != 14
                || base.groupSummaries().size() != 6) {
            throw new IllegalArgumentException("Invalid Rule v6 attribution");
        }
        boolean hit = facts.hit();
        int ceiling = !hit || independent(base.ruleContributions())
                ? 100 : Math.max(base.riskScore(), 79);
        int raw = hit ? 50 : 0;
        int applied = Math.min(raw, Math.max(0, ceiling - base.riskScore()));
        int score = base.riskScore() + applied;
        List<RuleContributionResponse> rows = new ArrayList<>(base.ruleContributions());
        rows.add(new RuleContributionResponse(RuleId.R015, 15, hit, raw, applied));
        List<RuleScoreGroupSummaryResponse> groups = new ArrayList<>(base.groupSummaries());
        groups.add(new RuleScoreGroupSummaryResponse(RuleScoreGroupId.open_banking_chain,
                raw, 50, applied, raw - applied));
        RuleRiskLevel level = score < 20 ? RuleRiskLevel.LOW
                : score < 50 ? RuleRiskLevel.MEDIUM
                : score < 80 ? RuleRiskLevel.HIGH : RuleRiskLevel.CRITICAL;
        return new RuleScoringResultResponse("scoring-policy-v7", score, level,
                List.copyOf(rows), List.copyOf(groups));
    }

    public static int scoreCeiling(RuleScoringResultResponse score) {
        var rows = score.ruleContributions();
        if (rows.size() != 15) throw new IllegalArgumentException("Rule v7 needs 15 rows");
        boolean flow = rows.get(12).matched() || rows.get(13).matched()
                || rows.get(14).matched();
        return !flow || independent(rows) ? 100 : Math.max(score.riskScore(), 79);
    }

    private static boolean independent(List<RuleContributionResponse> rows) {
        return rows.get(1).matched() || rows.get(2).matched()
                || rows.get(4).matched() || rows.get(6).matched()
                || rows.get(7).matched() || rows.get(8).matched()
                || rows.get(9).matched();
    }
}
