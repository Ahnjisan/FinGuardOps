package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.core.JsonProcessingException;
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
public class TransactionIntakeSnapshotCodec {

    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "transactionId",
            "processingStatus",
            "riskLevel",
            "riskResponseOutcome",
            "adoptedDetectionResultId",
            "caseId",
            "createdAt"
    );

    private final ObjectMapper objectMapper;

    public TransactionIntakeSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode encode(TransactionIntakeSnapshot snapshot) {
        ObjectNode encoded = objectMapper.createObjectNode();
        encoded.put("transactionId", snapshot.transactionId().toString());
        encoded.put("processingStatus", snapshot.processingStatus().name());
        putNullableText(encoded, "riskLevel", snapshot.riskLevel());
        putNullableText(
                encoded,
                "riskResponseOutcome",
                snapshot.riskResponseOutcome()
        );
        putNullableText(
                encoded,
                "adoptedDetectionResultId",
                snapshot.adoptedDetectionResultId()
        );
        putNullableText(encoded, "caseId", snapshot.caseId());
        encoded.put(
                "createdAt",
                DateTimeFormatter.ISO_INSTANT.format(snapshot.createdAt())
        );
        return encoded;
    }

    public TransactionIntakeSnapshot decode(String snapshotJson) {
        if (snapshotJson == null) {
            throw new InvalidTransactionIntakeSnapshotException();
        }

        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            requireExactFields(root);

            UUID transactionId = parseTransactionId(
                    requiredText(root, "transactionId")
            );
            TransactionProcessingStatus processingStatus =
                    parseProcessingStatus(requiredText(
                            root,
                            "processingStatus"
                    ));
            Instant createdAt = parseCreatedAt(
                    requiredText(root, "createdAt")
            );

            return new TransactionIntakeSnapshot(
                    transactionId,
                    processingStatus,
                    nullableText(root, "riskLevel"),
                    nullableText(root, "riskResponseOutcome"),
                    nullableText(root, "adoptedDetectionResultId"),
                    nullableText(root, "caseId"),
                    createdAt
            );
        } catch (InvalidTransactionIntakeSnapshotException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidTransactionIntakeSnapshotException(exception);
        }
    }

    private void requireExactFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        Set<String> actualFields = new HashSet<>();
        root.fieldNames().forEachRemaining(actualFields::add);
        if (!SNAPSHOT_FIELDS.equals(actualFields)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
    }

    private UUID parseTransactionId(String value) {
        UUID transactionId = UUID.fromString(value);
        if (transactionId.version() != 4 || transactionId.variant() != 2) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return transactionId;
    }

    private TransactionProcessingStatus parseProcessingStatus(String value) {
        if (!TransactionProcessingStatus.RECEIVED.name().equals(value)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return TransactionProcessingStatus.RECEIVED;
    }

    private Instant parseCreatedAt(String value) {
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

    private String nullableText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return value.textValue();
    }

    private void putNullableText(
            ObjectNode target,
            String field,
            String value
    ) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }
}
