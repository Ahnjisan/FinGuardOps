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

/** Exact future v6 set: unchanged v5 plus two proven source-to-use signals. */
public final class ScenarioFundFlowRuleVersionContract {
    public static final List<String> CODES = List.of(
            RuleV1ContractRegistry.VERIFIED_INBOUND_ATM_DRAIN,
            RuleV1ContractRegistry.VERIFIED_LOAN_NEW_RECIPIENT_DRAIN);
    private static final List<String> SIGNALS = List.of(
            "APPROVED_INBOUND_LINKED_ATM_EIGHTY_PERCENT",
            "APPROVED_LOAN_LINKED_NEW_RECIPIENT_EIGHTY_PERCENT");

    private ScenarioFundFlowRuleVersionContract() { }

    public static void validate(List<RuleVersionSnapshotRequest> snapshots,
                                Instant cutoff) {
        if (snapshots == null || snapshots.size() != 14
                || RulePolicyVersion.from(snapshots) != 6) {
            throw new IllegalArgumentException("Invalid Rule v6 set");
        }
        ScenarioAggregationRuleVersionContract.validate(
                snapshots.subList(0, 12), cutoff);
        Set<UUID> ruleIds = new HashSet<>();
        Set<UUID> versionIds = new HashSet<>();
        for (RuleVersionSnapshotRequest item : snapshots) {
            if (!ruleIds.add(item.fraudRuleId())
                    || !versionIds.add(item.ruleVersionId())) {
                throw new IllegalArgumentException("Duplicate Rule v6 identity");
            }
        }
        for (int i = 0; i < 2; i++) {
            RuleVersionSnapshotRequest item = snapshots.get(i + 12);
            if (!CODES.get(i).equals(item.ruleCode())
                    || !item.ruleCode().equals(item.reasonCode())
                    || item.versionNumber() != 1 || item.weight() != 50
                    || item.lifecycleStatus() != RuleLifecycleStatus.ACTIVE
                    || item.status() != RuleVersionStatus.PUBLISHED
                    || item.effectiveFrom().isAfter(cutoff)
                    || item.effectiveTo() != null
                    && !cutoff.isBefore(item.effectiveTo())
                    || !"SCN006-007-contract-v1".equals(item.conditionDefinition()
                            .path("contractVersion").asText())
                    || !SIGNALS.get(i).equals(item.conditionDefinition()
                            .path("signal").asText())) {
                throw new IllegalArgumentException("Invalid Rule v6 binding");
            }
            RuleConditionDefinition.from(item.ruleCode(), item.conditionDefinition());
        }
    }
}
