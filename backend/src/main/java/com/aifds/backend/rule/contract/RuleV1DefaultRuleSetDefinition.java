package com.aifds.backend.rule.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RuleV1DefaultRuleSetDefinition {

    private static final List<DefaultRule> RULES = List.of(
            rule(
                    RuleV1ExecutionPlanRegistry.CanonicalRuleId.R001,
                    1,
                    "10000000-0000-4000-8000-000000000001",
                    "20000000-0000-4000-8000-000000000001",
                    RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
            ),
            rule(
                    RuleV1ExecutionPlanRegistry.CanonicalRuleId.R002,
                    2,
                    "10000000-0000-4000-8000-000000000002",
                    "20000000-0000-4000-8000-000000000002",
                    RuleV1ContractRegistry
                            .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
            ),
            rule(
                    RuleV1ExecutionPlanRegistry.CanonicalRuleId.R003,
                    3,
                    "10000000-0000-4000-8000-000000000003",
                    "20000000-0000-4000-8000-000000000003",
                    RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT
            ),
            rule(
                    RuleV1ExecutionPlanRegistry.CanonicalRuleId.R004,
                    4,
                    "10000000-0000-4000-8000-000000000004",
                    "20000000-0000-4000-8000-000000000004",
                    RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
            )
    );

    private RuleV1DefaultRuleSetDefinition() {
    }

    public static List<DefaultRule> rules() {
        return RULES;
    }

    private static DefaultRule rule(
            RuleV1ExecutionPlanRegistry.CanonicalRuleId ruleId,
            int canonicalOrder,
            String fraudRuleId,
            String ruleVersionId,
            String ruleCode
    ) {
        RuleV1ExecutionPlanRegistry.RuleCapability capability =
                RuleV1ExecutionPlanRegistry.capabilityFor(ruleCode);
        if (capability.ruleId() != ruleId
                || capability.canonicalOrder() != canonicalOrder) {
            throw new IllegalStateException(
                    "Default Rule v1 definition is not canonical"
            );
        }
        return new DefaultRule(
                ruleId,
                canonicalOrder,
                UUID.fromString(fraudRuleId),
                UUID.fromString(ruleVersionId),
                ruleCode,
                1
        );
    }

    public record DefaultRule(
            RuleV1ExecutionPlanRegistry.CanonicalRuleId ruleId,
            int canonicalOrder,
            UUID fraudRuleId,
            UUID ruleVersionId,
            String ruleCode,
            int versionNumber
    ) {

        public DefaultRule {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            Objects.requireNonNull(fraudRuleId, "fraudRuleId must not be null");
            Objects.requireNonNull(
                    ruleVersionId,
                    "ruleVersionId must not be null"
            );
            if (ruleCode == null || ruleCode.isBlank()) {
                throw new IllegalArgumentException("ruleCode must not be blank");
            }
            if (canonicalOrder < 1 || versionNumber < 1) {
                throw new IllegalArgumentException(
                        "canonicalOrder and versionNumber must be positive"
                );
            }
        }
    }
}
