package com.aifds.backend.scenario.mock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Authenticated local Mock input; source and storage time are server owned. */
public record MockFactRequest(
        UUID factId,
        MockFactType factType,
        String externalCustomerRef,
        String accountRef,
        String deviceRef,
        String beneficiaryRef,
        UUID behaviorEventId,
        Instant occurredAt,
        Instant coverageFrom,
        Instant coverageThrough,
        String assertionState,
        String limitScope,
        BigDecimal previousLimit,
        BigDecimal newLimit,
        String countryCode
) {
    private static final Set<String> FIELDS = Set.of("factId", "factType",
            "externalCustomerRef", "accountRef", "deviceRef", "beneficiaryRef",
            "behaviorEventId", "occurredAt", "coverageFrom", "coverageThrough",
            "assertionState", "limitScope", "previousLimit", "newLimit", "countryCode");

    /** Optional fields may be omitted; strict global creator settings must not change this wire. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MockFactRequest fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Invalid Mock fact body");
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown Mock fact field");
        });
        return new MockFactRequest(uuid(node, "factId"), type(node),
                text(node, "externalCustomerRef"), text(node, "accountRef"),
                text(node, "deviceRef"), text(node, "beneficiaryRef"),
                uuid(node, "behaviorEventId"), instant(node, "occurredAt"),
                instant(node, "coverageFrom"), instant(node, "coverageThrough"),
                text(node, "assertionState"), text(node, "limitScope"),
                decimal(node, "previousLimit"), decimal(node, "newLimit"),
                text(node, "countryCode"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid Mock fact field");
        return value.textValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() && !value.isTextual()) {
            throw new IllegalArgumentException("Invalid Mock fact amount");
        }
        return new BigDecimal(value.asText());
    }

    private static MockFactType type(JsonNode node) {
        String value = text(node, "factType");
        return value == null ? null : MockFactType.valueOf(value);
    }
}
