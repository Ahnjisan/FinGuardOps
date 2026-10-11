package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleVersionStatus;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.RuleConditionDefinition;

import java.time.Instant;
import java.util.List;

public final class ScenarioOpenBankingRuleVersionContract {
    public static final String CODE =
            RuleV1ContractRegistry.VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND;
    public static final String SIGNAL =
            "TWO_OWNED_EXTERNAL_ACCOUNTS_CONCENTRATE_AND_RESEND";

    private ScenarioOpenBankingRuleVersionContract() { }

    public static void validate(List<RuleVersionSnapshotRequest> snapshots, Instant cutoff) {
        if (snapshots == null || snapshots.size() != 15
                || RulePolicyVersion.from(snapshots) != 7) {
            throw new IllegalArgumentException("Invalid Rule v7 set");
        }
        ScenarioFundFlowRuleVersionContract.validate(snapshots.subList(0, 14), cutoff);
        var item = snapshots.get(14);
        if (snapshots.subList(0, 14).stream().anyMatch(prior ->
                prior.fraudRuleId().equals(item.fraudRuleId())
                || prior.ruleVersionId().equals(item.ruleVersionId()))) {
            throw new IllegalArgumentException("Duplicate Rule v7 identity");
        }
        if (!CODE.equals(item.ruleCode()) || !CODE.equals(item.reasonCode())
                || item.versionNumber() != 1 || item.weight() != 50
                || item.lifecycleStatus() != RuleLifecycleStatus.ACTIVE
                || item.status() != RuleVersionStatus.PUBLISHED
                || item.effectiveFrom().isAfter(cutoff)
                || item.effectiveTo() != null && !cutoff.isBefore(item.effectiveTo())
                || !"SCN008-contract-v1".equals(item.conditionDefinition()
                        .path("contractVersion").asText())
                || !SIGNAL.equals(item.conditionDefinition().path("signal").asText())) {
            throw new IllegalArgumentException("Invalid Rule v7 binding");
        }
        RuleConditionDefinition.from(CODE, item.conditionDefinition());
    }
}
