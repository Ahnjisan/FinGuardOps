package com.aifds.backend.rule.contract;

import com.aifds.backend.rule.entity.RuleConditionDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RuleV1ExecutionPlanRegistry {

    public static final int EXECUTION_WINDOW_SECONDS = 86_400;
    public static final String AMOUNT_THRESHOLD = "10000000";

    private static final Map<String, RuleCapability> CAPABILITIES = Map.of(
            RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            new RuleCapability(
                    CanonicalRuleId.R001,
                    1,
                    RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                    15,
                    ScoreGroup.AMOUNT,
                    0,
                    Set.of(),
                    Set.of()
            ),
            RuleV1ContractRegistry.RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            new RuleCapability(
                    CanonicalRuleId.R002,
                    2,
                    RuleV1ContractRegistry.RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                    20,
                    ScoreGroup.SECURITY,
                    EXECUTION_WINDOW_SECONDS,
                    Set.of(CanonicalRuleId.R001),
                    Set.of(RequiredBehaviorEventType.DEVICE_REGISTERED)
            ),
            RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            new RuleCapability(
                    CanonicalRuleId.R003,
                    3,
                    RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                    40,
                    ScoreGroup.SECURITY,
                    EXECUTION_WINDOW_SECONDS,
                    Set.of(CanonicalRuleId.R001),
                    Set.of(
                            RequiredBehaviorEventType.PASSWORD_CHANGED,
                            RequiredBehaviorEventType.TRANSFER_LIMIT_CHANGED
                    )
            ),
            RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
            new RuleCapability(
                    CanonicalRuleId.R004,
                    4,
                    RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                    10,
                    ScoreGroup.SECURITY,
                    EXECUTION_WINDOW_SECONDS,
                    Set.of(),
                    Set.of(RequiredBehaviorEventType.BENEFICIARY_REGISTERED)
            )
    );

    private RuleV1ExecutionPlanRegistry() {
    }

    public static RuleCapability capabilityFor(String ruleCode) {
        RuleCapability capability = CAPABILITIES.get(ruleCode);
        if (capability == null) {
            throw new IllegalArgumentException(
                    "Unsupported Rule v1 ruleCode: " + ruleCode
            );
        }
        return capability;
    }

    public static void requireExecutionCompatible(
            String ruleCode,
            String reasonCode,
            int weight,
            JsonNode conditionDefinition
    ) {
        RuleCapability capability = capabilityFor(ruleCode);
        if (!capability.reasonCode().equals(reasonCode)) {
            throw new IllegalArgumentException(
                    "Rule v1 reasonCode is not executable for " + ruleCode
            );
        }
        if (capability.weight() != weight) {
            throw new IllegalArgumentException(
                    "Rule v1 weight is not executable for " + ruleCode
            );
        }

        JsonNode condition = RuleConditionDefinition.from(
                ruleCode,
                conditionDefinition
        ).toJson();
        if (capability.ruleId() == CanonicalRuleId.R001) {
            if (!AMOUNT_THRESHOLD.equals(
                    condition.path("amountThreshold").textValue()
            )) {
                throw new IllegalArgumentException(
                        "Rule v1 amountThreshold is not executable for "
                                + ruleCode
                );
            }
        } else if (condition.path("windowSeconds").intValue()
                != EXECUTION_WINDOW_SECONDS) {
            throw new IllegalArgumentException(
                    "Rule v1 windowSeconds is not executable for " + ruleCode
            );
        }
    }

    public static List<CanonicalRule> canonicalize(
            List<RuleVersionIdentity> identities
    ) {
        List<RuleVersionIdentity> values = List.copyOf(
                Objects.requireNonNull(identities, "identities must not be null")
        );
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "Rule v1 execution plan must not be empty"
            );
        }

        Set<UUID> fraudRuleIds = new HashSet<>();
        Set<UUID> ruleVersionIds = new HashSet<>();
        Set<String> ruleCodes = new HashSet<>();
        Set<CanonicalRuleId> ruleIds = EnumSet.noneOf(CanonicalRuleId.class);
        List<CanonicalRuleCandidate> candidates = new ArrayList<>();
        for (RuleVersionIdentity identity : values) {
            Objects.requireNonNull(
                    identity,
                    "identities must not contain null"
            );
            identity.requireValid();
            RuleCapability capability = capabilityFor(identity.ruleCode());
            if (!fraudRuleIds.add(identity.fraudRuleId())) {
                throw new IllegalArgumentException("Duplicate fraudRuleId");
            }
            if (!ruleVersionIds.add(identity.ruleVersionId())) {
                throw new IllegalArgumentException("Duplicate ruleVersionId");
            }
            boolean duplicateRuleCode = !ruleCodes.add(identity.ruleCode());
            boolean duplicateRuleId = !ruleIds.add(capability.ruleId());
            if (duplicateRuleCode || duplicateRuleId) {
                throw new IllegalArgumentException(
                        "Duplicate ruleCode or mapped RuleId"
                );
            }
            candidates.add(new CanonicalRuleCandidate(identity, capability));
        }

        for (CanonicalRuleCandidate candidate : candidates) {
            if (!ruleIds.containsAll(candidate.capability().dependencies())) {
                throw new IllegalArgumentException(
                        "Rule v1 dependency is not satisfied for "
                                + candidate.identity().ruleCode()
                );
            }
        }

        candidates.sort(Comparator.comparingInt(
                candidate -> candidate.capability().canonicalOrder()
        ));
        List<CanonicalRule> canonical = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            CanonicalRuleCandidate candidate = candidates.get(index);
            canonical.add(new CanonicalRule(
                    index + 1,
                    candidate.identity(),
                    candidate.capability()
            ));
        }
        return List.copyOf(canonical);
    }

    public enum CanonicalRuleId {
        R001,
        R002,
        R003,
        R004
    }

    public enum ScoreGroup {
        AMOUNT,
        SECURITY
    }

    public enum RequiredBehaviorEventType {
        DEVICE_REGISTERED,
        PASSWORD_CHANGED,
        TRANSFER_LIMIT_CHANGED,
        BENEFICIARY_REGISTERED
    }

    public record RuleCapability(
            CanonicalRuleId ruleId,
            int canonicalOrder,
            String reasonCode,
            int weight,
            ScoreGroup scoreGroup,
            int windowSeconds,
            Set<CanonicalRuleId> dependencies,
            Set<RequiredBehaviorEventType> requiredBehaviorEventTypes
    ) {

        public RuleCapability {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            reasonCode = requireText(reasonCode, "reasonCode");
            Objects.requireNonNull(scoreGroup, "scoreGroup must not be null");
            if (windowSeconds < 0) {
                throw new IllegalArgumentException(
                        "windowSeconds must not be negative"
                );
            }
            dependencies = Set.copyOf(dependencies);
            requiredBehaviorEventTypes = Set.copyOf(requiredBehaviorEventTypes);
        }
    }

    public record RuleVersionIdentity(
            UUID fraudRuleId,
            UUID ruleVersionId,
            String ruleCode,
            int versionNumber
    ) {

        private void requireValid() {
            Objects.requireNonNull(fraudRuleId, "fraudRuleId must not be null");
            Objects.requireNonNull(
                    ruleVersionId,
                    "ruleVersionId must not be null"
            );
            requireText(ruleCode, "ruleCode");
            if (versionNumber < 1) {
                throw new IllegalArgumentException(
                        "versionNumber must be positive"
                );
            }
        }
    }

    public record CanonicalRule(
            int executionOrder,
            RuleVersionIdentity identity,
            RuleCapability capability
    ) {
    }

    private record CanonicalRuleCandidate(
            RuleVersionIdentity identity,
            RuleCapability capability
    ) {
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
