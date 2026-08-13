package com.aifds.backend.rule.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleV1ExecutionPlanRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assignsCanonicalOrderToSupportedSubsequence() {
        List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonical =
                RuleV1ExecutionPlanRegistry.canonicalize(
                        List.of(identity(4), identity(3), identity(1))
                );

        assertThat(canonical)
                .extracting(rule -> rule.capability().ruleId())
                .containsExactly(
                        RuleV1ExecutionPlanRegistry.CanonicalRuleId.R001,
                        RuleV1ExecutionPlanRegistry.CanonicalRuleId.R003,
                        RuleV1ExecutionPlanRegistry.CanonicalRuleId.R004
                );
        assertThat(canonical)
                .extracting(
                        RuleV1ExecutionPlanRegistry.CanonicalRule
                                ::executionOrder
                )
                .containsExactly(1, 2, 3);
    }

    @Test
    void allowsIndependentR004Only() {
        assertThat(RuleV1ExecutionPlanRegistry.canonicalize(
                List.of(identity(4))
        )).singleElement().satisfies(rule -> {
            assertThat(rule.executionOrder()).isEqualTo(1);
            assertThat(rule.capability().ruleId())
                    .isEqualTo(
                            RuleV1ExecutionPlanRegistry.CanonicalRuleId.R004
                    );
        });
    }

    @Test
    void rejectsEmptyUnsupportedAndMissingDependencyPlans() {
        assertThatThrownBy(() -> RuleV1ExecutionPlanRegistry.canonicalize(
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleV1ExecutionPlanRegistry.canonicalize(
                List.of(new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "UNKNOWN",
                        1
                ))
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleV1ExecutionPlanRegistry.canonicalize(
                List.of(identity(2))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dependency");
        assertThatThrownBy(() -> RuleV1ExecutionPlanRegistry.canonicalize(
                List.of(identity(3))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dependency");
    }

    @Test
    void rejectsEveryDuplicateIdentityAndNonPositiveVersion() {
        RuleV1ExecutionPlanRegistry.RuleVersionIdentity r001 = identity(1);
        assertDuplicate(List.of(r001, new RuleV1ExecutionPlanRegistry
                .RuleVersionIdentity(
                r001.fraudRuleId(),
                UUID.randomUUID(),
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                1
        )), "fraudRuleId");
        assertDuplicate(List.of(r001, new RuleV1ExecutionPlanRegistry
                .RuleVersionIdentity(
                UUID.randomUUID(),
                r001.ruleVersionId(),
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                1
        )), "ruleVersionId");
        assertDuplicate(List.of(r001, new RuleV1ExecutionPlanRegistry
                .RuleVersionIdentity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                r001.ruleCode(),
                1
        )), "ruleCode");
        assertDuplicate(List.of(r001, new RuleV1ExecutionPlanRegistry
                .RuleVersionIdentity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                r001.ruleCode(),
                2
        )), "RuleId");
        assertThatThrownBy(() -> RuleV1ExecutionPlanRegistry.canonicalize(
                List.of(new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                        0
                ))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void validatesCurrentFastApiCapabilityWithoutNarrowingDraftContract() {
        ObjectNode r001 = amountCondition();
        RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                r001
        );

        ObjectNode unsupportedThreshold = r001.deepCopy()
                .put("amountThreshold", "20000000");
        assertThatThrownBy(() ->
                RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        15,
                        unsupportedThreshold
                )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountThreshold");
        assertThatThrownBy(() ->
                RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        99,
                        r001
                )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight");

        ObjectNode unsupportedWindow = beneficiaryCondition()
                .put("windowSeconds", 60);
        assertThatThrownBy(() ->
                RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                        RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                        RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                        10,
                        unsupportedWindow
                )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");
    }

    @Test
    void exposesExactBehaviorRequirementsAndExecutionWindows() {
        assertThat(capability(1).requiredBehaviorEventTypes()).isEmpty();
        assertThat(capability(1).windowSeconds()).isZero();
        assertThat(capability(2).requiredBehaviorEventTypes()).containsExactly(
                RuleV1ExecutionPlanRegistry.RequiredBehaviorEventType
                        .DEVICE_REGISTERED
        );
        assertThat(capability(3).requiredBehaviorEventTypes())
                .containsExactlyInAnyOrder(
                        RuleV1ExecutionPlanRegistry.RequiredBehaviorEventType
                                .PASSWORD_CHANGED,
                        RuleV1ExecutionPlanRegistry.RequiredBehaviorEventType
                                .TRANSFER_LIMIT_CHANGED
                );
        assertThat(capability(4).requiredBehaviorEventTypes()).containsExactly(
                RuleV1ExecutionPlanRegistry.RequiredBehaviorEventType
                        .BENEFICIARY_REGISTERED
        );
        assertThat(List.of(capability(2), capability(3), capability(4)))
                .allSatisfy(value -> assertThat(value.windowSeconds())
                        .isEqualTo(86_400));
    }

    private void assertDuplicate(
            List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities,
            String field
    ) {
        assertThatThrownBy(() ->
                RuleV1ExecutionPlanRegistry.canonicalize(identities)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }

    private RuleV1ExecutionPlanRegistry.RuleVersionIdentity identity(int value) {
        return new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                UUID.fromString(
                        "10000000-0000-4000-8000-00000000000" + value
                ),
                UUID.fromString(
                        "20000000-0000-4000-8000-00000000000" + value
                ),
                switch (value) {
                    case 1 -> RuleV1ContractRegistry
                            .TRANSFER_ABSOLUTE_HIGH_AMOUNT;
                    case 2 -> RuleV1ContractRegistry
                            .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT;
                    case 3 -> RuleV1ContractRegistry
                            .RECENT_SECURITY_CHANGE_HIGH_AMOUNT;
                    case 4 -> RuleV1ContractRegistry
                            .RECENT_BENEFICIARY_TRANSFER;
                    default -> throw new IllegalArgumentException();
                },
                1
        );
    }

    private RuleV1ExecutionPlanRegistry.RuleCapability capability(int value) {
        return RuleV1ExecutionPlanRegistry.capabilityFor(
                identity(value).ruleCode()
        );
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }

    private ObjectNode beneficiaryCondition() {
        return objectMapper.createObjectNode()
                .put("eventType", "BENEFICIARY_REGISTERED")
                .put("windowSeconds", 86_400)
                .put(
                        "matchPolicy",
                        "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY"
                )
                .put(
                        "selectionPolicy",
                        "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                );
    }
}
