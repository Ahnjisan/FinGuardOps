package com.aifds.backend.rule.client.dto;

import com.aifds.backend.scenario.ScenarioAggregationFacts;
import com.aifds.backend.scenario.ScenarioFacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Internal v4 wire for the future Rule v5 aggregation set. */
public record RuleAnalysisRequestV4(
        Instant evaluationCutoffAt,
        RuleTransactionSnapshotRequest transaction,
        List<RuleBehaviorEventSnapshotRequest> behaviorEvents,
        List<RuleVersionSnapshotRequest> ruleVersions,
        ExternalRiskSnapshotRequest externalRisk,
        ScenarioFacts scenarioFacts,
        ScenarioAggregationFacts aggregationFacts
) {
    public RuleAnalysisRequestV4 {
        RuleAnalysisRequestV3 validated = new RuleAnalysisRequestV3(
                evaluationCutoffAt, transaction, behaviorEvents, ruleVersions,
                externalRisk, scenarioFacts);
        evaluationCutoffAt = validated.evaluationCutoffAt();
        transaction = validated.transaction();
        behaviorEvents = validated.behaviorEvents();
        ruleVersions = validated.ruleVersions();
        externalRisk = validated.externalRisk();
        scenarioFacts = validated.scenarioFacts();
        Objects.requireNonNull(aggregationFacts, "aggregationFacts must not be null");
    }

    public RuleAnalysisRequestV3 base() {
        return new RuleAnalysisRequestV3(evaluationCutoffAt, transaction,
                behaviorEvents, ruleVersions.subList(0, 10), externalRisk, scenarioFacts);
    }

    public static RuleAnalysisRequestV4 from(RuleAnalysisRequestV2 base,
            ScenarioFacts scenarioFacts, ScenarioAggregationFacts aggregationFacts) {
        return new RuleAnalysisRequestV4(base.evaluationCutoffAt(), base.transaction(),
                base.behaviorEvents(), base.ruleVersions(), base.externalRisk(),
                scenarioFacts, aggregationFacts);
    }
}
