package com.aifds.backend.rule.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuleConditionDefinition {

    public static final String TRANSFER_ABSOLUTE_HIGH_AMOUNT =
            "TRANSFER_ABSOLUTE_HIGH_AMOUNT";
    public static final String RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT =
            "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT";
    public static final String RECENT_SECURITY_CHANGE_HIGH_AMOUNT =
            "RECENT_SECURITY_CHANGE_HIGH_AMOUNT";
    public static final String RECENT_BENEFICIARY_TRANSFER =
            "RECENT_BENEFICIARY_TRANSFER";

    private static final String HIGH_AMOUNT_PREREQUISITE =
            TRANSFER_ABSOLUTE_HIGH_AMOUNT;
    private static final String LATEST_EVENT_SELECTION =
            "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC";
    private static final String SECURITY_SELECTION =
            "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_"
                    + "LATEST_PASSWORD_THEN_EVENT_ID_ASC";
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("^[1-9][0-9]{0,14}$");
    private static final Map<String, Set<String>> ALLOWED_FIELDS = Map.of(
            TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            Set.of(
                    "transactionTypes",
                    "currencyCode",
                    "amountThreshold"
            ),
            RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            Set.of(
                    "prerequisiteRuleCode",
                    "eventType",
                    "windowSeconds",
                    "matchPolicy",
                    "selectionPolicy"
            ),
            RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            Set.of(
                    "prerequisiteRuleCode",
                    "passwordEventType",
                    "transferLimitEventType",
                    "windowSeconds",
                    "matchPolicy",
                    "sequencePolicy",
                    "selectionPolicy"
            ),
            RECENT_BENEFICIARY_TRANSFER,
            Set.of(
                    "eventType",
                    "windowSeconds",
                    "matchPolicy",
                    "selectionPolicy"
            )
    );

    private final JsonNode value;

    private RuleConditionDefinition(JsonNode value) {
        this.value = value.deepCopy();
    }

    public static RuleConditionDefinition from(
            String ruleCode,
            JsonNode value
    ) {
        if (ruleCode == null) {
            throw new IllegalArgumentException("ruleCode must not be null");
        }
        if (value == null || !value.isObject() || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "conditionDefinition must be a non-empty JSON object"
            );
        }

        Set<String> expectedFields = ALLOWED_FIELDS.get(ruleCode);
        if (expectedFields == null) {
            throw new IllegalArgumentException(
                    "Unsupported Rule v1 ruleCode: " + ruleCode
            );
        }
        Set<String> actualFields = new HashSet<>();
        value.fieldNames().forEachRemaining(actualFields::add);
        if (!expectedFields.equals(actualFields)) {
            throw new IllegalArgumentException(
                    "conditionDefinition fields do not match " + ruleCode
            );
        }

        switch (ruleCode) {
            case TRANSFER_ABSOLUTE_HIGH_AMOUNT -> validateR001(value);
            case RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT -> validateR002(value);
            case RECENT_SECURITY_CHANGE_HIGH_AMOUNT -> validateR003(value);
            case RECENT_BENEFICIARY_TRANSFER -> validateR004(value);
            default -> throw new IllegalArgumentException(
                    "Unsupported Rule v1 ruleCode: " + ruleCode
            );
        }
        rejectNestedValuesExceptTransactionTypes(value);
        return new RuleConditionDefinition(value);
    }

    public JsonNode toJson() {
        return value.deepCopy();
    }

    private static void validateR001(JsonNode root) {
        requireExactStringArray(
                root,
                "transactionTypes",
                Set.of("ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER")
        );
        requireExactText(root, "currencyCode", "KRW");
        JsonNode threshold = root.get("amountThreshold");
        if (threshold == null
                || !threshold.isTextual()
                || !AMOUNT_PATTERN.matcher(threshold.textValue()).matches()) {
            throw new IllegalArgumentException(
                    "amountThreshold must be a positive canonical integer "
                            + "string within NUMERIC(19,4) integer range"
            );
        }
        BigInteger amount = new BigInteger(threshold.textValue());
        if (amount.compareTo(new BigInteger("999999999999999")) > 0) {
            throw new IllegalArgumentException(
                    "amountThreshold exceeds NUMERIC(19,4) integer range"
            );
        }
    }

    private static void validateR002(JsonNode root) {
        requireExactText(
                root,
                "prerequisiteRuleCode",
                HIGH_AMOUNT_PREREQUISITE
        );
        requireExactText(root, "eventType", "DEVICE_REGISTERED");
        requirePositiveInteger(root, "windowSeconds");
        requireExactText(root, "matchPolicy", "SAME_CUSTOMER_AND_DEVICE");
        requireExactText(root, "selectionPolicy", LATEST_EVENT_SELECTION);
    }

    private static void validateR003(JsonNode root) {
        requireExactText(
                root,
                "prerequisiteRuleCode",
                HIGH_AMOUNT_PREREQUISITE
        );
        requireExactText(root, "passwordEventType", "PASSWORD_CHANGED");
        requireExactText(
                root,
                "transferLimitEventType",
                "TRANSFER_LIMIT_CHANGED"
        );
        requirePositiveInteger(root, "windowSeconds");
        requireExactText(
                root,
                "matchPolicy",
                "SAME_CUSTOMER_AND_SENDER_ACCOUNT"
        );
        requireExactText(
                root,
                "sequencePolicy",
                "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED"
        );
        requireExactText(root, "selectionPolicy", SECURITY_SELECTION);
    }

    private static void validateR004(JsonNode root) {
        requireExactText(root, "eventType", "BENEFICIARY_REGISTERED");
        requirePositiveInteger(root, "windowSeconds");
        requireExactText(
                root,
                "matchPolicy",
                "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY"
        );
        requireExactText(root, "selectionPolicy", LATEST_EVENT_SELECTION);
    }

    private static void requireExactText(
            JsonNode root,
            String field,
            String expected
    ) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || !expected.equals(value.textValue())) {
            throw new IllegalArgumentException(
                    field + " must equal " + expected
            );
        }
    }

    private static void requirePositiveInteger(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 1) {
            throw new IllegalArgumentException(
                    field + " must be a positive 32-bit integer"
            );
        }
    }

    private static void requireExactStringArray(
            JsonNode root,
            String field,
            Set<String> expectedValues
    ) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must be a non-empty string array"
            );
        }
        Set<String> actualValues = new HashSet<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || !actualValues.add(element.textValue())) {
                throw new IllegalArgumentException(
                        field + " must contain unique strings"
                );
            }
        }
        if (!expectedValues.equals(actualValues)) {
            throw new IllegalArgumentException(
                    field + " does not match the Rule v1 contract"
            );
        }
    }

    private static void rejectNestedValuesExceptTransactionTypes(
            JsonNode root
    ) {
        root.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNull()
                    || value.isObject()
                    || (value.isArray()
                    && !"transactionTypes".equals(entry.getKey()))) {
                throw new IllegalArgumentException(
                        "conditionDefinition contains an invalid nested value"
                );
            }
        });
    }
}
