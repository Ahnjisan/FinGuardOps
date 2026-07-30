package com.aifds.backend.transaction.service;

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
import java.util.Set;
import java.util.UUID;

@Component
class TransactionIntakeSnapshotBodyCodec {

    static final Set<String> BODY_FIELDS = Set.of(
            "transactionId",
            "processingStatus",
            "riskLevel",
            "riskResponseOutcome",
            "adoptedDetectionResultId",
            "caseId",
            "createdAt"
    );

    private final ObjectMapper objectMapper;

    TransactionIntakeSnapshotBodyCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode encode(TransactionIntakeSnapshot snapshot) {
        requireCurrentResponseContract(snapshot);

        ObjectNode encoded = objectMapper.createObjectNode();
        encoded.put("transactionId", snapshot.transactionId().toString());
        encoded.put("processingStatus", snapshot.processingStatus().name());
        encoded.putNull("riskLevel");
        encoded.putNull("riskResponseOutcome");
        encoded.putNull("adoptedDetectionResultId");
        encoded.putNull("caseId");
        encoded.put(
                "createdAt",
                DateTimeFormatter.ISO_INSTANT.format(snapshot.createdAt())
        );
        return encoded;
    }

    TransactionIntakeSnapshot decode(JsonNode root) {
        requireExactFields(root);

        UUID transactionId = parseTransactionId(
                requiredText(root, "transactionId")
        );
        TransactionProcessingStatus processingStatus =
                parseProcessingStatus(requiredText(
                        root,
                        "processingStatus"
                ));
        requireNull(root, "riskLevel");
        requireNull(root, "riskResponseOutcome");
        requireNull(root, "adoptedDetectionResultId");
        requireNull(root, "caseId");
        Instant createdAt = parseUtcInstant(
                requiredText(root, "createdAt")
        );

        return new TransactionIntakeSnapshot(
                transactionId,
                processingStatus,
                null,
                null,
                null,
                null,
                createdAt
        );
    }

    boolean hasExactFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        Set<String> actualFields = new HashSet<>();
        root.fieldNames().forEachRemaining(actualFields::add);
        return BODY_FIELDS.equals(actualFields);
    }

    private void requireCurrentResponseContract(
            TransactionIntakeSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot must not be null");
        }
        if (snapshot.processingStatus()
                != TransactionProcessingStatus.RECEIVED
                || snapshot.riskLevel() != null
                || snapshot.riskResponseOutcome() != null
                || snapshot.adoptedDetectionResultId() != null
                || snapshot.caseId() != null) {
            throw new IllegalArgumentException(
                    "Snapshot must match the current transaction create response contract"
            );
        }
    }

    private void requireExactFields(JsonNode root) {
        if (!hasExactFields(root)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
    }

    private UUID parseTransactionId(String value) {
        try {
            UUID transactionId = UUID.fromString(value);
            if (!transactionId.toString().equals(value)
                    || transactionId.version() != 4
                    || transactionId.variant() != 2) {
                throw new InvalidTransactionIntakeSnapshotException();
            }
            return transactionId;
        } catch (IllegalArgumentException exception) {
            throw new InvalidTransactionIntakeSnapshotException(exception);
        }
    }

    private TransactionProcessingStatus parseProcessingStatus(String value) {
        if (!TransactionProcessingStatus.RECEIVED.name().equals(value)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return TransactionProcessingStatus.RECEIVED;
    }

    private Instant parseUtcInstant(String value) {
        if (!value.endsWith("Z")) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidTransactionIntakeSnapshotException(exception);
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return value.textValue();
    }

    private void requireNull(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNull()) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
    }
}
