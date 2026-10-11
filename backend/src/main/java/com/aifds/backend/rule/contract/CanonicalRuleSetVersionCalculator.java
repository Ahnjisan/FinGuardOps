package com.aifds.backend.rule.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.scenario.ScenarioRuleVersionContract;
import com.aifds.backend.scenario.ScenarioAggregationRuleVersionContract;

public final class CanonicalRuleSetVersionCalculator {

    private static final String PLAN_VERSION = "rule-plan-v1";

    public String calculate(
            List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities
    ) {
        return HexFormat.of().formatHex(sha256(canonicalInput(identities)));
    }

    public String calculateV4(List<RuleVersionSnapshotRequest> snapshots) {
        if (snapshots.size() != 10) throw new IllegalArgumentException("Rule v4 requires ten versions");
        StringBuilder input = new StringBuilder("rule-plan-v4\n");
        for (int i = 0; i < snapshots.size(); i++) {
            RuleVersionSnapshotRequest item = snapshots.get(i);
            if (!ScenarioRuleVersionContract.CODES.get(i).equals(item.ruleCode())) {
                throw new IllegalArgumentException("Rule v4 order mismatch");
            }
            input.append(i + 1).append('\t').append(item.ruleVersionId()).append('\t')
                    .append(item.ruleCode()).append('\t').append("R")
                    .append(String.format("%03d", i + 1)).append('\t')
                    .append(item.versionNumber()).append('\n');
        }
        return HexFormat.of().formatHex(sha256(input.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public String calculateV5(List<RuleVersionSnapshotRequest> snapshots) {
        if (snapshots.size() != 12) {
            throw new IllegalArgumentException("Rule v5 requires twelve versions");
        }
        StringBuilder input = new StringBuilder("rule-plan-v5\n");
        for (int i = 0; i < snapshots.size(); i++) {
            RuleVersionSnapshotRequest item = snapshots.get(i);
            String expected = i < 10 ? ScenarioRuleVersionContract.CODES.get(i)
                    : ScenarioAggregationRuleVersionContract.CODES.get(i - 10);
            if (!expected.equals(item.ruleCode())) {
                throw new IllegalArgumentException("Rule v5 order mismatch");
            }
            input.append(i + 1).append('\t').append(item.ruleVersionId()).append('\t')
                    .append(item.ruleCode()).append('\t').append("R")
                    .append(String.format("%03d", i + 1)).append('\t')
                    .append(item.versionNumber()).append('\n');
        }
        return HexFormat.of().formatHex(sha256(input.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public byte[] canonicalInput(
            List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities
    ) {
        List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonicalRules =
                RuleV1ExecutionPlanRegistry.canonicalize(identities);
        StringBuilder input = new StringBuilder(PLAN_VERSION).append('\n');
        for (RuleV1ExecutionPlanRegistry.CanonicalRule rule : canonicalRules) {
            input.append(rule.executionOrder()).append('\t')
                    .append(rule.identity().ruleVersionId()).append('\t')
                    .append(rule.identity().ruleCode()).append('\t')
                    .append(rule.capability().ruleId()).append('\t')
                    .append(rule.identity().versionNumber()).append('\n');
        }
        return input.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
