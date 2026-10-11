package com.aifds.backend.rule.client.dto;

import com.aifds.backend.scenario.ScenarioAggregationFacts;
import com.aifds.backend.scenario.ScenarioFacts;
import com.aifds.backend.scenario.ScenarioFundFlowFacts;
import com.aifds.backend.scenario.ScenarioOpenBankingFacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Internal v6 wire for the future Rule v7 open banking chain. */
public record RuleAnalysisRequestV6(
        Instant evaluationCutoffAt,
        RuleTransactionSnapshotRequest transaction,
        List<RuleBehaviorEventSnapshotRequest> behaviorEvents,
        List<RuleVersionSnapshotRequest> ruleVersions,
        ExternalRiskSnapshotRequest externalRisk,
        ScenarioFacts scenarioFacts,
        ScenarioAggregationFacts aggregationFacts,
        ScenarioFundFlowFacts fundFlowFacts,
        ScenarioOpenBankingFacts openBankingFacts
) {
    public RuleAnalysisRequestV6 {
        new RuleAnalysisRequestV3(evaluationCutoffAt, transaction,
                behaviorEvents, ruleVersions, externalRisk, scenarioFacts);
        Objects.requireNonNull(aggregationFacts, "aggregationFacts must not be null");
        Objects.requireNonNull(fundFlowFacts, "fundFlowFacts must not be null");
        Objects.requireNonNull(openBankingFacts, "openBankingFacts must not be null");
        ruleVersions = List.copyOf(ruleVersions);
    }

    public RuleAnalysisRequestV5 base() {
        return new RuleAnalysisRequestV5(evaluationCutoffAt, transaction, behaviorEvents,
                ruleVersions.subList(0, 14), externalRisk, scenarioFacts,
                aggregationFacts, fundFlowFacts);
    }

    public static RuleAnalysisRequestV6 from(RuleAnalysisRequestV2 base,
            ScenarioFacts scenarioFacts, ScenarioAggregationFacts aggregationFacts,
            ScenarioFundFlowFacts fundFlowFacts,
            ScenarioOpenBankingFacts openBankingFacts) {
        return new RuleAnalysisRequestV6(base.evaluationCutoffAt(), base.transaction(),
                base.behaviorEvents(), base.ruleVersions(), base.externalRisk(),
                scenarioFacts, aggregationFacts, fundFlowFacts, openBankingFacts);
    }
}
