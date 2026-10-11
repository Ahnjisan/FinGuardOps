package com.aifds.backend.scenario.mock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Opaque local Mock assertion; receipt time and source are server owned. */
public record MockOpenAccountRequest(UUID assertionId, String externalCustomerRef,
        String externalAccountRef, String registrationState, String ownershipState,
        Instant occurredAt) {
    private static final Set<String> FIELDS = Set.of("assertionId",
            "externalCustomerRef", "externalAccountRef", "registrationState",
            "ownershipState", "occurredAt");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MockOpenAccountRequest fromJson(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != FIELDS.size()) {
            throw new IllegalArgumentException("Invalid Mock open account body");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown Mock open account field");
            }
        });
        try {
            return new MockOpenAccountRequest(UUID.fromString(text(node, "assertionId")),
                    text(node, "externalCustomerRef"),
                    text(node, "externalAccountRef"), text(node, "registrationState"),
                    text(node, "ownershipState"), Instant.parse(text(node, "occurredAt")));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Mock open account body", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException("Invalid Mock open account field");
        }
        return value.textValue();
    }
}
