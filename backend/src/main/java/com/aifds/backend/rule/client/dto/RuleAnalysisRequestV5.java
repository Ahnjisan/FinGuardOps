package com.aifds.backend.rule.client.dto;

import com.aifds.backend.scenario.ScenarioAggregationFacts;
import com.aifds.backend.scenario.ScenarioFacts;
import com.aifds.backend.scenario.ScenarioFundFlowFacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Internal v5 wire for the future Rule v6 source-to-use set. */
public record RuleAnalysisRequestV5(
        Instant evaluationCutoffAt,
        RuleTransactionSnapshotRequest transaction,
        List<RuleBehaviorEventSnapshotRequest> behaviorEvents,
        List<RuleVersionSnapshotRequest> ruleVersions,
        ExternalRiskSnapshotRequest externalRisk,
        ScenarioFacts scenarioFacts,
        ScenarioAggregationFacts aggregationFacts,
        ScenarioFundFlowFacts fundFlowFacts
) {
    public RuleAnalysisRequestV5 {
        new RuleAnalysisRequestV3(evaluationCutoffAt, transaction,
                behaviorEvents, ruleVersions, externalRisk, scenarioFacts);
        Objects.requireNonNull(aggregationFacts, "aggregationFacts must not be null");
        Objects.requireNonNull(fundFlowFacts, "fundFlowFacts must not be null");
        ruleVersions = List.copyOf(ruleVersions);
    }

    public RuleAnalysisRequestV4 base() {
        return new RuleAnalysisRequestV4(evaluationCutoffAt, transaction,
                behaviorEvents, ruleVersions.subList(0, 12), externalRisk,
                scenarioFacts, aggregationFacts);
    }

    public static RuleAnalysisRequestV5 from(RuleAnalysisRequestV2 base,
            ScenarioFacts scenarioFacts, ScenarioAggregationFacts aggregationFacts,
            ScenarioFundFlowFacts fundFlowFacts) {
        return new RuleAnalysisRequestV5(base.evaluationCutoffAt(), base.transaction(),
                base.behaviorEvents(), base.ruleVersions(), base.externalRisk(),
                scenarioFacts, aggregationFacts, fundFlowFacts);
    }
}
