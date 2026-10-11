package com.aifds.backend.scenario.mock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Strict wire shape; source identity and receipt time are server owned. */
public record MockFundLinkRequest(UUID linkId, String scenarioCode,
        UUID sourceTransactionId, UUID targetTransactionId,
        String externalCustomerRef, String accountRef,
        String assertionState, Instant occurredAt) {
    private static final Set<String> FIELDS = Set.of("linkId", "scenarioCode",
            "sourceTransactionId", "targetTransactionId", "externalCustomerRef",
            "accountRef", "assertionState", "occurredAt");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MockFundLinkRequest fromJson(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != FIELDS.size()) {
            throw new IllegalArgumentException("Invalid Mock fund link body");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown Mock fund link field");
            }
        });
        try {
            return new MockFundLinkRequest(
                    UUID.fromString(text(node, "linkId")), text(node, "scenarioCode"),
                    UUID.fromString(text(node, "sourceTransactionId")),
                    UUID.fromString(text(node, "targetTransactionId")),
                    text(node, "externalCustomerRef"), text(node, "accountRef"),
                    text(node, "assertionState"), Instant.parse(text(node, "occurredAt")));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Mock fund link body", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException("Invalid Mock fund link field");
        }
        return value.textValue();
    }
}
