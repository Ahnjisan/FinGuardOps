package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoreGroupId;
import com.aifds.backend.rule.client.dto.RuleScoreGroupSummaryResponse;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository.Window;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioAggregationRulePolicyTest {
    @Test
    void oneWindowStaysMediumAndTwoWindowsShareFortyPointCap() {
        var proof = UUID.randomUUID();
        var outbound = new Window(3, 3, new BigDecimal("3000000"), 0, proof);
        var inbound = new Window(3, 3, new BigDecimal("5000000"), 0, proof);
        var absent = new Window(1, 1, BigDecimal.ONE, 0, null);

        var one = ScenarioAggregationRulePolicy.evaluate(emptyBase(),
                new ScenarioAggregationFacts("SCN004-005-contract-v1", outbound, absent));
        assertThat(one.riskScore()).isEqualTo(25);
        assertThat(one.riskLevel()).isEqualTo(RuleRiskLevel.MEDIUM);

        var both = ScenarioAggregationRulePolicy.evaluate(emptyBase(),
                new ScenarioAggregationFacts("SCN004-005-contract-v1", outbound, inbound));
        assertThat(both.riskScore()).isEqualTo(40);
        assertThat(both.riskLevel()).isEqualTo(RuleRiskLevel.MEDIUM);
        assertThat(both.ruleContributions().get(10).appliedContribution()).isEqualTo(25);
        assertThat(both.ruleContributions().get(11).appliedContribution()).isEqualTo(15);
        assertThat(both.groupSummaries().get(4).reduction()).isEqualTo(10);
        assertThat(ScenarioAggregationRulePolicy.scoreCeiling(both)).isEqualTo(49);
        var independentRows = new java.util.ArrayList<>(both.ruleContributions());
        independentRows.set(3, new RuleContributionResponse(RuleId.R004, 4,
                true, 10, 10));
        var independentlySupported = new RuleScoringResultResponse("scoring-policy-v5",
                50, RuleRiskLevel.HIGH, independentRows, both.groupSummaries());
        assertThat(ScenarioAggregationRulePolicy.scoreCeiling(independentlySupported))
                .isEqualTo(100);
    }

    private RuleScoringResultResponse emptyBase() {
        List<RuleContributionResponse> rules = Arrays.stream(RuleId.values()).limit(10)
                .map(id -> new RuleContributionResponse(id, id.ordinal() + 1, false, 0, 0))
                .toList();
        List<RuleScoreGroupSummaryResponse> groups = Arrays.stream(RuleScoreGroupId.values())
                .limit(4).map(id -> new RuleScoreGroupSummaryResponse(id, 0, 100, 0, 0))
                .toList();
        return new RuleScoringResultResponse("scoring-policy-v4", 0, RuleRiskLevel.LOW,
                rules, groups);
    }
}
