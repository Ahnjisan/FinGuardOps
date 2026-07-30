package com.aifds.backend.detection.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
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
    private static final Map<String, Set<String>> ALLOWED_FIELDS = Map.of(
            TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            Set.of("observedAmount", "amountThreshold"),
            RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            Set.of(
                    "observedAmount",
                    "amountThreshold",
                    "eventId",
                    "deviceRegisteredAt",
                    "elapsedSeconds",
                    "windowSeconds"
            ),
            RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            Set.of(
                    "observedAmount",
                    "amountThreshold",
                    "passwordChangedEventId",
                    "passwordChangedAt",
                    "transferLimitChangedEventId",
                    "transferLimitChangedAt",
                    "elapsedSeconds",
                    "windowSeconds"
            ),
            RECENT_BENEFICIARY_TRANSFER,
            Set.of(
                    "observedAmount",
                    "eventId",
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
            JsonNode value,
            Instant evaluationCutoffAt
    ) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(
                evaluationCutoffAt,
                "evaluationCutoffAt must not be null"
        );
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
        if (value.has("passwordChangedAt")) {
            requireUtcInstant(value, "passwordChangedAt");
        }
        if (value.has("transferLimitChangedAt")) {
            requireUtcInstant(value, "transferLimitChangedAt");
        }
        if (value.has("beneficiaryRegisteredAt")) {
            requireUtcInstant(value, "beneficiaryRegisteredAt");
        }
        if (value.has("eventId")) {
            requireBehaviorEventId(value, "eventId");
        }
        if (value.has("passwordChangedEventId")) {
            requireBehaviorEventId(value, "passwordChangedEventId");
        }
        if (value.has("transferLimitChangedEventId")) {
            requireBehaviorEventId(value, "transferLimitChangedEventId");
        }
        if (value.has("elapsedSeconds")) {
            requireNonNegativeInteger(value, "elapsedSeconds");
        }
        if (value.has("windowSeconds")) {
            requireNonNegativeInteger(value, "windowSeconds");
        }
        rejectNestedValues(value);
        validateBehaviorTiming(reasonCode, value, evaluationCutoffAt);

        return new RuleEvidenceObservationSummary(value);
    }

    public JsonNode toJson() {
        return value.deepCopy();
    }

    public static boolean supportsReasonCode(String reasonCode) {
        return ALLOWED_FIELDS.containsKey(reasonCode);
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

    private static Instant requireUtcInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || !value.textValue().endsWith("Z")) {
            throw new IllegalArgumentException(
                    field + " must be a UTC Instant"
            );
        }
        try {
            return Instant.parse(value.textValue());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    field + " must be a UTC Instant",
                    exception
            );
        }
    }

    private static void requireBehaviorEventId(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical lowercase UUID v4"
            );
        }

        String text = value.textValue();
        try {
            java.util.UUID uuid = java.util.UUID.fromString(text);
            if (!uuid.toString().equals(text)
                    || uuid.version() != 4
                    || uuid.variant() != 2) {
                throw new IllegalArgumentException(
                        field + " must be a canonical lowercase UUID v4"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    field + " must be a canonical lowercase UUID v4",
                    exception
            );
        }
    }

    private static void validateBehaviorTiming(
            String reasonCode,
            JsonNode value,
            Instant evaluationCutoffAt
    ) {
        if (RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT.equals(reasonCode)) {
            requireNotAfterCutoff(
                    requireUtcInstant(value, "deviceRegisteredAt"),
                    evaluationCutoffAt,
                    "deviceRegisteredAt"
            );
            return;
        }
        if (RECENT_BENEFICIARY_TRANSFER.equals(reasonCode)) {
            requireNotAfterCutoff(
                    requireUtcInstant(value, "beneficiaryRegisteredAt"),
                    evaluationCutoffAt,
                    "beneficiaryRegisteredAt"
            );
            return;
        }
        if (!RECENT_SECURITY_CHANGE_HIGH_AMOUNT.equals(reasonCode)) {
            return;
        }

        Instant passwordChangedAt = requireUtcInstant(
                value,
                "passwordChangedAt"
        );
        Instant transferLimitChangedAt = requireUtcInstant(
                value,
                "transferLimitChangedAt"
        );
        if (passwordChangedAt.isAfter(transferLimitChangedAt)) {
            throw new IllegalArgumentException(
                    "passwordChangedAt must not be after transferLimitChangedAt"
            );
        }
        requireNotAfterCutoff(
                transferLimitChangedAt,
                evaluationCutoffAt,
                "transferLimitChangedAt"
        );

        long elapsedSeconds = value.get("elapsedSeconds").longValue();
        long expectedElapsedSeconds = Duration.between(
                transferLimitChangedAt,
                evaluationCutoffAt
        ).getSeconds();
        if (elapsedSeconds != expectedElapsedSeconds) {
            throw new IllegalArgumentException(
                    "elapsedSeconds must equal evaluation cutoff minus "
                            + "transferLimitChangedAt"
            );
        }

        long windowSeconds = value.get("windowSeconds").longValue();
        if (Duration.between(passwordChangedAt, evaluationCutoffAt).getSeconds()
                > windowSeconds) {
            throw new IllegalArgumentException(
                    "security change events must be within windowSeconds"
            );
        }
    }

    private static void requireNotAfterCutoff(
            Instant occurredAt,
            Instant evaluationCutoffAt,
            String field
    ) {
        if (occurredAt.isAfter(evaluationCutoffAt)) {
            throw new IllegalArgumentException(
                    field + " must not be after evaluationCutoffAt"
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
