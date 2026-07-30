package com.aifds.backend.detection.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuleEvidenceObservationSummary {

    public static final String TRANSFER_ABSOLUTE_HIGH_AMOUNT =
            "TRANSFER_ABSOLUTE_HIGH_AMOUNT";
    public static final String RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT =
            "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT";
    public static final String RECENT_SECURITY_CHANGE_HIGH_AMOUNT =
            "RECENT_SECURITY_CHANGE_HIGH_AMOUNT";
    public static final String RECENT_BENEFICIARY_TRANSFER =
            "RECENT_BENEFICIARY_TRANSFER";

    private static final Pattern KRW_INTEGER_PATTERN =
            Pattern.compile("^(0|[1-9][0-9]{0,18})$");
    private static final Set<String> SECURITY_EVENT_TYPES =
            Set.of("PASSWORD_CHANGED", "TRANSFER_LIMIT_CHANGED");
    private static final Map<String, Set<String>> ALLOWED_FIELDS = Map.of(
            TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            Set.of("observedAmount", "amountThreshold"),
            RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            Set.of(
                    "observedAmount",
                    "amountThreshold",
                    "deviceRegisteredAt",
                    "elapsedSeconds",
                    "windowSeconds"
            ),
            RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            Set.of(
                    "observedAmount",
                    "amountThreshold",
                    "securityEventType",
                    "securityChangedAt",
                    "elapsedSeconds",
                    "windowSeconds"
            ),
            RECENT_BENEFICIARY_TRANSFER,
            Set.of(
                    "observedAmount",
                    "beneficiaryRegisteredAt",
                    "elapsedSeconds",
                    "windowSeconds"
            )
    );

    private final JsonNode value;

    private RuleEvidenceObservationSummary(JsonNode value) {
        this.value = value.deepCopy();
    }

    public static RuleEvidenceObservationSummary from(
            String reasonCode,
            JsonNode value
    ) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (value == null || !value.isObject() || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "observationSummary must be a non-empty JSON object"
            );
        }

        Set<String> expectedFields = ALLOWED_FIELDS.get(reasonCode);
        if (expectedFields == null) {
            throw new IllegalArgumentException(
                    "Unsupported Rule v1 reasonCode: " + reasonCode
            );
        }

        Set<String> actualFields = new HashSet<>();
        value.fieldNames().forEachRemaining(actualFields::add);
        if (!expectedFields.equals(actualFields)) {
            throw new IllegalArgumentException(
                    "observationSummary fields do not match " + reasonCode
            );
        }

        requireKrwInteger(value, "observedAmount");
        if (value.has("amountThreshold")) {
            requireKrwInteger(value, "amountThreshold");
        }
        if (value.has("deviceRegisteredAt")) {
            requireUtcInstant(value, "deviceRegisteredAt");
        }
        if (value.has("securityChangedAt")) {
            requireUtcInstant(value, "securityChangedAt");
        }
        if (value.has("beneficiaryRegisteredAt")) {
            requireUtcInstant(value, "beneficiaryRegisteredAt");
        }
        if (value.has("securityEventType")) {
            JsonNode eventType = value.get("securityEventType");
            if (!eventType.isTextual()
                    || !SECURITY_EVENT_TYPES.contains(eventType.textValue())) {
                throw new IllegalArgumentException(
                        "securityEventType must be a supported security event"
                );
            }
        }
        if (value.has("elapsedSeconds")) {
            requireNonNegativeInteger(value, "elapsedSeconds");
        }
        if (value.has("windowSeconds")) {
            requireNonNegativeInteger(value, "windowSeconds");
        }
        rejectNestedValues(value);

        return new RuleEvidenceObservationSummary(value);
    }

    public JsonNode toJson() {
        return value.deepCopy();
    }

    private static void requireKrwInteger(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || !KRW_INTEGER_PATTERN.matcher(value.textValue()).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a KRW integer string"
            );
        }
    }

    private static void requireUtcInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || !value.textValue().endsWith("Z")) {
            throw new IllegalArgumentException(
                    field + " must be a UTC Instant"
            );
        }
        try {
            Instant.parse(value.textValue());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    field + " must be a UTC Instant",
                    exception
            );
        }
    }

    private static void requireNonNegativeInteger(
            JsonNode root,
            String field
    ) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw new IllegalArgumentException(
                    field + " must be a non-negative integer"
            );
        }
    }

    private static void rejectNestedValues(JsonNode root) {
        for (JsonNode value : root) {
            if (value.isContainerNode() || value.isNull()) {
                throw new IllegalArgumentException(
                        "observationSummary values must be non-null scalars"
                );
            }
        }
    }
}
