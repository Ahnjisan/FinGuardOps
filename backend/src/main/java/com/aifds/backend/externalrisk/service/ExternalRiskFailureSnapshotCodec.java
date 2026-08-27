package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.exception.InvalidExternalRiskFailureSnapshotException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

@Component
public class ExternalRiskFailureSnapshotCodec {

    static final String SNAPSHOT_TYPE = "external-risk-failure";
    static final String RESPONSE_SCHEMA_VERSION =
            "transaction-create-error-v1";
    static final String CODEC_VERSION =
            "external-risk-failure-snapshot-envelope-v1";
    static final int MAX_CANONICAL_UTF8_BYTES = 4096;

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "snapshotType",
            "responseBody",
            "httpStatus",
            "failureCategory",
            "responseSchemaVersion",
            "codecVersion",
            "finalizedAt"
    );
    private static final Set<String> RESPONSE_BODY_FIELDS = Set.of(
            "code",
            "message",
            "fieldErrors"
    );

    private final ObjectMapper objectMapper;

    public ExternalRiskFailureSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode encode(ExternalRiskFailureSnapshot snapshot) {
        if (snapshot == null) {
            throw invalid();
        }

        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.put("code", snapshot.responseBody().code());
        responseBody.put("message", snapshot.responseBody().message());
        responseBody.putArray("fieldErrors");

        ObjectNode root = objectMapper.createObjectNode();
        root.put("snapshotType", SNAPSHOT_TYPE);
        root.set("responseBody", responseBody);
        root.put("httpStatus", snapshot.httpStatus());
        root.put("failureCategory", snapshot.failureCategory().name());
        root.put("responseSchemaVersion", RESPONSE_SCHEMA_VERSION);
        root.put("codecVersion", CODEC_VERSION);
        root.put(
                "finalizedAt",
                DateTimeFormatter.ISO_INSTANT.format(snapshot.finalizedAt())
        );
        requireCanonicalSize(root);
        return root.deepCopy();
    }

    public ExternalRiskFailureSnapshot decode(
            String snapshotJson,
            String failureCode,
            Instant finishedAt
    ) {
        if (snapshotJson == null || failureCode == null || finishedAt == null) {
            throw invalid();
        }

        final JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(snapshotJson);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
        return decode(root, failureCode, finishedAt);
    }

    public ExternalRiskFailureSnapshot decode(
            JsonNode snapshot,
            String failureCode,
            Instant finishedAt
    ) {
        if (snapshot == null || failureCode == null || finishedAt == null) {
            throw invalid();
        }

        try {
            requireCanonicalSize(snapshot);
            requireExactFields(snapshot, ENVELOPE_FIELDS);
            requireText(snapshot, "snapshotType", SNAPSHOT_TYPE);
            requireText(
                    snapshot,
                    "responseSchemaVersion",
                    RESPONSE_SCHEMA_VERSION
            );
            requireText(snapshot, "codecVersion", CODEC_VERSION);

            JsonNode responseBody = snapshot.get("responseBody");
            requireExactFields(responseBody, RESPONSE_BODY_FIELDS);
            String code = requiredText(responseBody, "code");
            String message = requiredText(responseBody, "message");
            JsonNode fieldErrors = responseBody.get("fieldErrors");
            if (fieldErrors == null
                    || !fieldErrors.isArray()
                    || !fieldErrors.isEmpty()) {
                throw invalid();
            }
            if (!code.equals(failureCode)) {
                throw invalid();
            }

            int httpStatus = requiredInteger(snapshot, "httpStatus");
            ExternalRiskFailureCategory category = requiredCategory(snapshot);
            Instant finalizedAt = requiredUtcInstant(snapshot, "finalizedAt");
            if (!finalizedAt.equals(finishedAt)) {
                throw invalid();
            }

            ExternalRiskFailureSnapshot decoded =
                    new ExternalRiskFailureSnapshot(
                            new ExternalRiskFailureSnapshot.ResponseBody(
                                    code,
                                    message,
                                    java.util.List.of()
                            ),
                            httpStatus,
                            category,
                            finalizedAt
                    );
            return decoded;
        } catch (InvalidExternalRiskFailureSnapshotException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private void requireExactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid();
        }
    }

    private void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(requiredText(node, field))) {
            throw invalid();
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw invalid();
        }
        return value.textValue();
    }

    private int requiredInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private ExternalRiskFailureCategory requiredCategory(JsonNode root) {
        try {
            return ExternalRiskFailureCategory.valueOf(
                    requiredText(root, "failureCategory")
            );
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
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    void requireCanonicalSize(JsonNode root) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(root);
            if (canonical.length > MAX_CANONICAL_UTF8_BYTES) {
                throw invalid();
            }
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    private InvalidExternalRiskFailureSnapshotException invalid() {
        return new InvalidExternalRiskFailureSnapshotException();
    }
}
