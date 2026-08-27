package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

@Component
class TransactionIntakeSnapshotEnvelopeV2Codec {

    static final String RESPONSE_SCHEMA_VERSION =
            "transaction-create-response-v2";
    static final String CODEC_VERSION =
            "transaction-intake-snapshot-envelope-v2";
    static final int SUPPORTED_HTTP_STATUS = 201;
    static final int MAX_CANONICAL_UTF8_BYTES = 4096;

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "responseBody",
            "httpStatus",
            "responseSchemaVersion",
            "codecVersion",
            "finalizedAt"
    );

    private final ObjectMapper objectMapper;
    private final TransactionFinalResponseSnapshotBodyCodec bodyCodec;

    TransactionIntakeSnapshotEnvelopeV2Codec(
            ObjectMapper objectMapper,
            TransactionFinalResponseSnapshotBodyCodec bodyCodec
    ) {
        this.objectMapper = objectMapper;
        this.bodyCodec = bodyCodec;
    }

    JsonNode encode(
            TransactionFinalResponseSnapshot snapshot,
            int httpStatus,
            Instant finalizedAt
    ) {
        if (snapshot == null || finalizedAt == null) {
            throw invalid();
        }
        if (httpStatus != SUPPORTED_HTTP_STATUS) {
            throw invalid();
        }

        ObjectNode canonical = canonicalEnvelope(
                snapshot,
                httpStatus,
                finalizedAt
        );
        requireCanonicalSize(canonical);
        return canonical.deepCopy();
    }

    TransactionIntakeSnapshotReplay decode(JsonNode root) {
        try {
            requireExactFields(root);
            requireVersion(
                    root,
                    "responseSchemaVersion",
                    RESPONSE_SCHEMA_VERSION
            );
            requireVersion(root, "codecVersion", CODEC_VERSION);
            int httpStatus = requiredHttpStatus(root);
            Instant finalizedAt = requiredUtcInstant(root, "finalizedAt");
            TransactionFinalResponseSnapshot snapshot = bodyCodec.decode(
                    root.get("responseBody")
            );

            ObjectNode canonical = canonicalEnvelope(
                    snapshot,
                    httpStatus,
                    finalizedAt
            );
            requireCanonicalSize(canonical);
            return new TransactionIntakeSnapshotReplay(
                    snapshot.toTransactionIntakeSnapshot(),
                    httpStatus
            );
        } catch (InvalidTransactionIntakeSnapshotException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    void requireCanonicalSize(JsonNode canonicalTree) {
        try {
            byte[] canonical = objectMapper.writer()
                    .without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsBytes(canonicalTree);
            if (canonical.length > MAX_CANONICAL_UTF8_BYTES) {
                throw invalid();
            }
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    private ObjectNode canonicalEnvelope(
            TransactionFinalResponseSnapshot snapshot,
            int httpStatus,
            Instant finalizedAt
    ) {
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

    private void requireExactFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw invalid();
        }
        Set<String> actualFields = new HashSet<>();
        root.fieldNames().forEachRemaining(actualFields::add);
        if (!ENVELOPE_FIELDS.equals(actualFields)) {
            throw invalid();
        }
    }

    private void requireVersion(
            JsonNode root,
            String field,
            String expected
    ) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || !expected.equals(value.textValue())) {
            throw invalid();
        }
    }

    private int requiredHttpStatus(JsonNode root) {
        JsonNode value = root.get("httpStatus");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() != SUPPORTED_HTTP_STATUS) {
            throw invalid();
        }
        return value.intValue();
    }

    private Instant requiredUtcInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isTextual()
                || value.textValue().isEmpty()
                || !value.textValue().endsWith("Z")) {
            throw invalid();
        }
        try {
            Instant parsed = Instant.parse(value.textValue());
            if (!DateTimeFormatter.ISO_INSTANT.format(parsed)
                    .equals(value.textValue())) {
                throw invalid();
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private InvalidTransactionIntakeSnapshotException invalid() {
        return new InvalidTransactionIntakeSnapshotException();
    }
}
