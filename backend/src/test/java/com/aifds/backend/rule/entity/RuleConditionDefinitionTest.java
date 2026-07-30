package com.aifds.backend.rule.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleConditionDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsEachExactRuleV1Definition() {
        assertAccepted(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountCondition()
        );
        assertAccepted(
                RuleConditionDefinition
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceCondition()
        );
        assertAccepted(
                RuleConditionDefinition.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securityCondition()
        );
        assertAccepted(
                RuleConditionDefinition.RECENT_BENEFICIARY_TRANSFER,
                beneficiaryCondition()
        );
    }

    @Test
    void rejectsUnknownMissingAndWrongTypedFields() {
        assertInvalid(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountCondition().put("unknown", true)
        );
        ObjectNode missing = deviceCondition();
        missing.remove("windowSeconds");
        assertInvalid(
                RuleConditionDefinition
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                missing
        );
        assertInvalid(
                RuleConditionDefinition.RECENT_BENEFICIARY_TRANSFER,
                beneficiaryCondition().put("windowSeconds", "86400")
        );
        assertInvalid(
                RuleConditionDefinition.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securityCondition().putObject("matchPolicy")
        );
    }

    @Test
    void validatesAmountAndWindowRanges() {
        assertInvalid(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountCondition().put("amountThreshold", "0")
        );
        assertInvalid(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountCondition().put(
                        "amountThreshold",
                        "1000000000000000"
                )
        );
        assertInvalid(
                RuleConditionDefinition
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceCondition().put("windowSeconds", 0)
        );
        assertInvalid(
                RuleConditionDefinition.RECENT_BENEFICIARY_TRANSFER,
                beneficiaryCondition().put(
                        "windowSeconds",
                        2147483648L
                )
        );
    }

    @Test
    void rejectsInvalidRuleSpecificValues() {
        assertInvalid(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountCondition().put("currencyCode", "USD")
        );
        assertInvalid(
                RuleConditionDefinition
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceCondition().put("eventType", "LOGIN")
        );
        assertInvalid(
                RuleConditionDefinition.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securityCondition().put(
                        "sequencePolicy",
                        "REVERSED"
                )
        );
        assertInvalid(
                RuleConditionDefinition.RECENT_BENEFICIARY_TRANSFER,
                beneficiaryCondition().put(
                        "selectionPolicy",
                        "UNORDERED"
                )
        );
    }

    @Test
    void rejectsNonObjectEmptyNullAndUnsupportedRule() {
        assertThatThrownBy(() -> RuleConditionDefinition.from(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createArrayNode()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleConditionDefinition.from(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createObjectNode()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleConditionDefinition.from(
                "UNSUPPORTED_RULE",
                amountCondition()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsDefensiveCopies() {
        ObjectNode input = amountCondition();
        RuleConditionDefinition definition = RuleConditionDefinition.from(
                RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                input
        );

        input.put("amountThreshold", "1");
        ObjectNode first = (ObjectNode) definition.toJson();
        first.put("amountThreshold", "2");

        assertThat(definition.toJson().get("amountThreshold").textValue())
                .isEqualTo("10000000");
    }

    private void assertAccepted(String ruleCode, ObjectNode condition) {
        assertThat(RuleConditionDefinition.from(ruleCode, condition).toJson())
                .isEqualTo(condition);
    }

    private void assertInvalid(String ruleCode, ObjectNode condition) {
        assertThatThrownBy(
                () -> RuleConditionDefinition.from(ruleCode, condition)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }

    private ObjectNode deviceCondition() {
        return objectMapper.createObjectNode()
                .put(
                        "prerequisiteRuleCode",
                        RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT
                )
                .put("eventType", "DEVICE_REGISTERED")
                .put("windowSeconds", 86400)
                .put("matchPolicy", "SAME_CUSTOMER_AND_DEVICE")
                .put(
                        "selectionPolicy",
                        "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                );
    }

    private ObjectNode securityCondition() {
        return objectMapper.createObjectNode()
                .put(
                        "prerequisiteRuleCode",
                        RuleConditionDefinition.TRANSFER_ABSOLUTE_HIGH_AMOUNT
                )
                .put("passwordEventType", "PASSWORD_CHANGED")
                .put(
                        "transferLimitEventType",
                        "TRANSFER_LIMIT_CHANGED"
                )
                .put("windowSeconds", 86400)
                .put(
                        "matchPolicy",
                        "SAME_CUSTOMER_AND_SENDER_ACCOUNT"
                )
                .put(
                        "sequencePolicy",
                        "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED"
                )
                .put(
                        "selectionPolicy",
                        "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_"
                                + "LATEST_PASSWORD_THEN_EVENT_ID_ASC"
                );
    }

    private ObjectNode beneficiaryCondition() {
        return objectMapper.createObjectNode()
                .put("eventType", "BENEFICIARY_REGISTERED")
                .put("windowSeconds", 86400)
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
