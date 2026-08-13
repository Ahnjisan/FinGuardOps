package com.aifds.backend.rule.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class CanonicalRuleSetVersionCalculator {

    private static final String PLAN_VERSION = "rule-plan-v1";

    public String calculate(
            List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities
    ) {
        return HexFormat.of().formatHex(sha256(canonicalInput(identities)));
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
