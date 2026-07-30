package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
class TransactionIntakeSnapshotEnvelopeCodec {

    static final String RESPONSE_SCHEMA_VERSION =
            "transaction-create-response-v1";
    static final String CODEC_VERSION =
            "transaction-intake-snapshot-envelope-v1";
    static final int SUPPORTED_HTTP_STATUS = 201;

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "responseBody",
            "httpStatus",
            "responseSchemaVersion",
            "codecVersion",
            "finalizedAt"
    );

    private final ObjectMapper objectMapper;
    private final TransactionIntakeSnapshotBodyCodec bodyCodec;

    TransactionIntakeSnapshotEnvelopeCodec(
            ObjectMapper objectMapper,
            TransactionIntakeSnapshotBodyCodec bodyCodec
    ) {
        this.objectMapper = objectMapper;
        this.bodyCodec = bodyCodec;
    }

    boolean isCandidate(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        for (String field : ENVELOPE_FIELDS) {
            if (root.has(field)) {
                return true;
            }
        }
        return false;
    }

    JsonNode encode(
            TransactionIntakeSnapshot snapshot,
            int httpStatus,
            Instant finalizedAt
    ) {
        if (httpStatus != SUPPORTED_HTTP_STATUS) {
            throw new IllegalArgumentException(
                    "Envelope v1 only supports HTTP status 201"
            );
        }
        if (finalizedAt == null) {
            throw new NullPointerException("finalizedAt must not be null");
        }

        ObjectNode encoded = objectMapper.createObjectNode();
        encoded.set("responseBody", bodyCodec.encode(snapshot));
        encoded.put("httpStatus", httpStatus);
        encoded.put("responseSchemaVersion", RESPONSE_SCHEMA_VERSION);
        encoded.put("codecVersion", CODEC_VERSION);
        encoded.put(
                "finalizedAt",
                DateTimeFormatter.ISO_INSTANT.format(finalizedAt)
        );
        return encoded;
    }

    TransactionIntakeSnapshotReplay decode(JsonNode root) {
        requireNoTraceId(root);
        requireExactFields(root);
        requireVersion(root, "codecVersion", CODEC_VERSION);
        requireVersion(
                root,
                "responseSchemaVersion",
                RESPONSE_SCHEMA_VERSION
        );

        JsonNode responseBody = root.get("responseBody");
        TransactionIntakeSnapshot snapshot = bodyCodec.decode(responseBody);
        int httpStatus = requiredHttpStatus(root);
        requiredUtcInstant(root, "finalizedAt");

        return new TransactionIntakeSnapshotReplay(snapshot, httpStatus);
    }

    private void requireExactFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        Set<String> actualFields = new HashSet<>();
        root.fieldNames().forEachRemaining(actualFields::add);
        if (!ENVELOPE_FIELDS.equals(actualFields)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
    }

    private void requireVersion(
            JsonNode root,
            String field,
            String supportedVersion
    ) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || !supportedVersion.equals(value.textValue())) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
    }

    private int requiredHttpStatus(JsonNode root) {
        JsonNode value = root.get("httpStatus");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() != SUPPORTED_HTTP_STATUS) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return value.intValue();
    }

    private Instant requiredUtcInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || value.textValue().isEmpty()
                || !value.textValue().endsWith("Z")) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        try {
            return Instant.parse(value.textValue());
        } catch (DateTimeParseException exception) {
            throw new InvalidTransactionIntakeSnapshotException(exception);
        }
    }

    private void requireNoTraceId(JsonNode node) {
        if (containsTraceId(node)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
    }

    private boolean containsTraceId(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if ("traceId".equals(field.getKey())
                        || containsTraceId(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsTraceId(element)) {
                    return true;
                }
            }
        }
        return false;
    }
}
