package com.aifds.backend.rule.client.dto;

import com.aifds.backend.scenario.ScenarioFacts;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Internal v3 wire; distinct from the persisted RuleVersion policy. */
public record RuleAnalysisRequestV3(
        Instant evaluationCutoffAt,
        RuleTransactionSnapshotRequest transaction,
        List<RuleBehaviorEventSnapshotRequest> behaviorEvents,
        List<RuleVersionSnapshotRequest> ruleVersions,
        ExternalRiskSnapshotRequest externalRisk,
        ScenarioFacts scenarioFacts
) {
    public RuleAnalysisRequestV3 {
        RuleAnalysisRequestV2 validated = new RuleAnalysisRequestV2(
                evaluationCutoffAt, transaction, behaviorEvents, ruleVersions, externalRisk);
        evaluationCutoffAt = validated.evaluationCutoffAt();
        transaction = validated.transaction();
        behaviorEvents = validated.behaviorEvents();
        ruleVersions = validated.ruleVersions();
        externalRisk = validated.externalRisk();
        Objects.requireNonNull(scenarioFacts, "scenarioFacts must not be null");
    }

    public static RuleAnalysisRequestV3 from(RuleAnalysisRequestV2 base,
                                             ScenarioFacts facts) {
        return new RuleAnalysisRequestV3(base.evaluationCutoffAt(), base.transaction(),
                base.behaviorEvents(), base.ruleVersions(), base.externalRisk(), facts);
    }
}
