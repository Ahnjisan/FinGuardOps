package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleVersionStatus;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.RuleConditionDefinition;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact future Rule v5 set: the unchanged ten v4 rules plus two window signals. */
public final class ScenarioAggregationRuleVersionContract {
    public static final List<String> CODES = List.of(
            RuleV1ContractRegistry.DISTRIBUTED_OUTBOUND_WINDOW,
            RuleV1ContractRegistry.MANY_CUSTOMER_INBOUND_WINDOW);
    private static final List<String> SIGNALS = List.of(
            "THREE_RECIPIENTS_TEN_MINUTES", "THREE_CUSTOMERS_TWENTY_FOUR_HOURS");

    private ScenarioAggregationRuleVersionContract() { }

    public static void validate(List<RuleVersionSnapshotRequest> snapshots, Instant cutoff) {
        if (snapshots == null || snapshots.size() != 12
                || RulePolicyVersion.from(snapshots) != 5) {
            throw new IllegalArgumentException("Invalid Rule v5 set");
        }
        ScenarioRuleVersionContract.validate(snapshots.subList(0, 10), cutoff);
        Set<UUID> ruleIds = new HashSet<>();
        Set<UUID> versionIds = new HashSet<>();
        for (RuleVersionSnapshotRequest item : snapshots) {
            if (!ruleIds.add(item.fraudRuleId()) || !versionIds.add(item.ruleVersionId())) {
                throw new IllegalArgumentException("Duplicate Rule v5 identity");
            }
        }
        for (int i = 0; i < 2; i++) {
            RuleVersionSnapshotRequest item = snapshots.get(i + 10);
            if (!CODES.get(i).equals(item.ruleCode())
                    || !item.ruleCode().equals(item.reasonCode())
                    || item.versionNumber() != 1 || item.weight() != 25
                    || item.lifecycleStatus() != RuleLifecycleStatus.ACTIVE
                    || item.status() != RuleVersionStatus.PUBLISHED
                    || item.effectiveFrom().isAfter(cutoff)
                    || item.effectiveTo() != null && !cutoff.isBefore(item.effectiveTo())
                    || !"SCN004-005-contract-v1".equals(item.conditionDefinition()
                            .path("contractVersion").asText())
                    || !SIGNALS.get(i).equals(item.conditionDefinition().path("signal").asText())) {
                throw new IllegalArgumentException("Invalid Rule v5 binding");
            }
            RuleConditionDefinition.from(item.ruleCode(), item.conditionDefinition());
        }
    }
}
