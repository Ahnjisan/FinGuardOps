package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.entity.RuleConditionDefinition;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public final class ScenarioRuleVersionContract {
    public static final List<String> CODES = List.of(
            "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            "RECENT_BENEFICIARY_TRANSFER",
            "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
            "CUSTOMER_BASELINE_HIGH_AMOUNT",
            "EXTERNAL_RISK_DEVICE_MATCH",
            "MOCK_APP_SECURITY_ALERT",
            "MATERIAL_LIMIT_UPLIFT",
            "MOCK_OVERSEAS_NETWORK");
    public static final List<Integer> WEIGHTS = List.of(15, 20, 50, 10, 40,
            20, 20, 15, 10, 10);

    private ScenarioRuleVersionContract() { }

    public static void validate(List<RuleVersionSnapshotRequest> snapshots, Instant cutoff) {
        if (RulePolicyVersion.from(snapshots) != 4 || snapshots.size() != 10) {
            throw new IllegalArgumentException("Invalid Rule v4 set");
        }
        Set<java.util.UUID> ruleIds = new HashSet<>();
        Set<java.util.UUID> versionIds = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            RuleVersionSnapshotRequest item = snapshots.get(i);
            if (!CODES.get(i).equals(item.ruleCode())
                    || !item.ruleCode().equals(item.reasonCode())
                    || WEIGHTS.get(i) != item.weight()
                    || !ruleIds.add(item.fraudRuleId())
                    || !versionIds.add(item.ruleVersionId())
                    || item.effectiveFrom().isAfter(cutoff)
                    || item.effectiveTo() != null && !cutoff.isBefore(item.effectiveTo())
                    || item.lifecycleStatus() != com.aifds.backend.rule.client.dto.RuleLifecycleStatus.ACTIVE
                    || item.status() != com.aifds.backend.rule.client.dto.RuleVersionStatus.PUBLISHED) {
                throw new IllegalArgumentException("Invalid Rule v4 binding");
            }
            RuleConditionDefinition.from(item.ruleCode(), item.conditionDefinition());
            if (i == 0 || i == 4) {
                RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                        item.ruleCode(), item.reasonCode(), item.weight(),
                        item.conditionDefinition());
            }
            if (i != 0 && i != 4
                    && !"SCN001-002-contract-v1".equals(
                            item.conditionDefinition().path("contractVersion").asText())) {
                throw new IllegalArgumentException("Rule v4 condition version mismatch");
            }
        }
    }
}
