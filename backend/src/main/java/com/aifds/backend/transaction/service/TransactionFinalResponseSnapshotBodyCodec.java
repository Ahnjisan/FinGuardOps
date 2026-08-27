package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
class TransactionFinalResponseSnapshotBodyCodec {

    static final Set<String> BODY_FIELDS = Set.of(
            "transactionId",
            "processingStatus",
            "riskLevel",
            "riskResponseOutcome",
            "adoptedDetectionResultId",
            "caseId",
            "createdAt"
    );

    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "traceid",
            "idempotencykey",
            "requestfingerprint",
            "externalcustomerref",
            "senderaccountref",
            "recipientaccountref",
            "accountref",
            "deviceref",
            "providerrequest",
            "providerresponse",
            "providerrawrequest",
            "providerrawresponse",
            "exception",
            "exceptionclass",
            "exceptionmessage",
            "stacktrace",
            "entity",
            "apikey",
            "token",
            "password",
            "authorization"
    );

    private final ObjectMapper objectMapper;

    TransactionFinalResponseSnapshotBodyCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode encode(TransactionFinalResponseSnapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot must not be null");
        }

        ObjectNode encoded = objectMapper.createObjectNode();
        encoded.put("transactionId", snapshot.transactionId().toString());
        encoded.put("processingStatus", snapshot.processingStatus().name());
        encoded.put("riskLevel", snapshot.riskLevel().name());
        encoded.put(
                "riskResponseOutcome",
                snapshot.riskResponseOutcome().name()
        );
        encoded.put(
                "adoptedDetectionResultId",
                snapshot.adoptedDetectionResultId().toString()
        );
        if (snapshot.caseId() == null) {
            encoded.putNull("caseId");
        } else {
            encoded.put("caseId", snapshot.caseId().toString());
        }
        encoded.put(
                "createdAt",
                DateTimeFormatter.ISO_INSTANT.format(snapshot.createdAt())
        );
        return encoded;
    }

    TransactionFinalResponseSnapshot decode(JsonNode root) {
        requireNoForbiddenFields(root);
        requireExactFields(root);

        UUID transactionId = requiredCanonicalUuid(root, "transactionId");
        TransactionProcessingStatus processingStatus =
                requiredProcessingStatus(root);
        RiskLevel riskLevel = requiredRiskLevel(root);
        RiskResponseOutcome riskResponseOutcome =
                requiredRiskResponseOutcome(root);
        UUID adoptedDetectionResultId = requiredCanonicalUuid(
                root,
                "adoptedDetectionResultId"
        );
        UUID caseId = nullableCanonicalUuid(root, "caseId");
        Instant createdAt = requiredUtcInstant(root, "createdAt");

        try {
            return new TransactionFinalResponseSnapshot(
                    transactionId,
                    processingStatus,
                    riskLevel,
                    riskResponseOutcome,
                    adoptedDetectionResultId,
                    caseId,
                    createdAt
            );
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private void requireExactFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw invalid();
        }
        Set<String> actualFields = new HashSet<>();
        root.fieldNames().forEachRemaining(actualFields::add);
        if (!BODY_FIELDS.equals(actualFields)) {
            throw invalid();
        }
    }

    private TransactionProcessingStatus requiredProcessingStatus(
            JsonNode root
    ) {
        String value = requiredText(root, "processingStatus");
        return switch (value) {
            case "APPROVED" -> TransactionProcessingStatus.APPROVED;
            case "ADDITIONAL_AUTH_REQUIRED" ->
                    TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED;
            case "HELD" -> TransactionProcessingStatus.HELD;
            default -> throw invalid();
        };
    }

    private RiskLevel requiredRiskLevel(JsonNode root) {
        try {
            return RiskLevel.valueOf(requiredText(root, "riskLevel"));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private RiskResponseOutcome requiredRiskResponseOutcome(JsonNode root) {
        try {
            return RiskResponseOutcome.valueOf(
                    requiredText(root, "riskResponseOutcome")
            );
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private UUID requiredCanonicalUuid(JsonNode root, String field) {
        return parseCanonicalUuid(requiredText(root, field));
    }

    private UUID nullableCanonicalUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            throw invalid();
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw invalid();
        }
        return parseCanonicalUuid(value.textValue());
    }

    private UUID parseCanonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)
                    || parsed.version() != 4
                    || parsed.variant() != 2) {
                throw invalid();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private Instant requiredUtcInstant(JsonNode root, String field) {
        String value = requiredText(root, field);
        if (!value.endsWith("Z")) {
            throw invalid();
        }
        try {
            Instant parsed = Instant.parse(value);
            if (!DateTimeFormatter.ISO_INSTANT.format(parsed).equals(value)) {
                throw invalid();
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw invalid();
        }
        return value.textValue();
    }

    private void requireNoForbiddenFields(JsonNode root) {
        if (containsForbiddenField(root)) {
            throw invalid();
        }
    }

    private boolean containsForbiddenField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (FORBIDDEN_FIELD_NAMES.contains(
                        field.getKey().toLowerCase(Locale.ROOT)
                ) || containsForbiddenField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsForbiddenField(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private InvalidTransactionIntakeSnapshotException invalid() {
        return new InvalidTransactionIntakeSnapshotException();
    }
}
