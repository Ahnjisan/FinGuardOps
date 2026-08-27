package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class TransactionIntakeSnapshotCodecTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-23T01:15:31.123456Z");
    private static final Instant FINALIZED_AT =
            Instant.parse("2026-07-23T01:15:33.654321Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionIntakeSnapshotBodyCodec bodyCodec =
            new TransactionIntakeSnapshotBodyCodec(objectMapper);
    private final TransactionIntakeLegacySnapshotCodec legacyCodec =
            new TransactionIntakeLegacySnapshotCodec(bodyCodec);
    private final TransactionIntakeSnapshotEnvelopeCodec envelopeCodec =
            new TransactionIntakeSnapshotEnvelopeCodec(
                    objectMapper,
                    bodyCodec
            );
    private final TransactionFinalResponseSnapshotBodyCodec finalBodyCodec =
            new TransactionFinalResponseSnapshotBodyCodec(objectMapper);
    private final TransactionIntakeSnapshotEnvelopeV2Codec envelopeV2Codec =
            new TransactionIntakeSnapshotEnvelopeV2Codec(
                    objectMapper,
                    finalBodyCodec
            );
    private final TransactionIntakeSnapshotCodec codec =
            new TransactionIntakeSnapshotCodec(
                    objectMapper,
                    legacyCodec,
                    envelopeCodec,
                    envelopeV2Codec
            );

    @Test
    void encodesVersionedEnvelopeWithExactMetadataAndBusinessBody() {
        JsonNode encoded = codec.encode(snapshot(), 201, FINALIZED_AT);

        assertThat(fieldNames(encoded)).containsExactlyInAnyOrder(
                "responseBody",
                "httpStatus",
                "responseSchemaVersion",
                "codecVersion",
                "finalizedAt"
        );
        assertThat(encoded.get("httpStatus").intValue()).isEqualTo(201);
        assertThat(encoded.get("responseSchemaVersion").textValue())
                .isEqualTo("transaction-create-response-v1");
        assertThat(encoded.get("codecVersion").textValue())
                .isEqualTo("transaction-intake-snapshot-envelope-v1");
        assertThat(encoded.get("finalizedAt").textValue())
                .isEqualTo("2026-07-23T01:15:33.654321Z");

        JsonNode responseBody = encoded.get("responseBody");
        assertThat(fieldNames(responseBody)).containsExactlyInAnyOrder(
                "transactionId",
                "processingStatus",
                "riskLevel",
                "riskResponseOutcome",
                "adoptedDetectionResultId",
                "caseId",
                "createdAt"
        );
        assertThat(responseBody.get("transactionId").textValue())
                .isEqualTo(TRANSACTION_ID.toString());
        assertThat(responseBody.get("processingStatus").textValue())
                .isEqualTo("RECEIVED");
        assertThat(responseBody.get("riskLevel").isNull()).isTrue();
        assertThat(responseBody.get("riskResponseOutcome").isNull()).isTrue();
        assertThat(responseBody.get("adoptedDetectionResultId").isNull())
                .isTrue();
        assertThat(responseBody.get("caseId").isNull()).isTrue();
        assertThat(responseBody.get("createdAt").textValue())
                .isEqualTo("2026-07-23T01:15:31.123456Z");
        assertThat(containsFieldRecursively(encoded, "traceId")).isFalse();
    }

    @Test
    void roundTripsEnvelopeWithStored201Status() throws Exception {
        String encoded = objectMapper.writeValueAsString(
                codec.encode(snapshot(), 201, FINALIZED_AT)
        );

        assertThat(codec.decode(encoded))
                .isEqualTo(new TransactionIntakeSnapshotReplay(
                        snapshot(),
                        201
                ));
    }

    @Test
    void decodesOnlyExactStrictLegacyShapeWith200Status() throws Exception {
        ObjectNode legacy = legacySnapshot();

        TransactionIntakeSnapshotReplay replay =
                codec.decode(objectMapper.writeValueAsString(legacy));

        assertThat(replay.snapshot()).isEqualTo(snapshot());
        assertThat(replay.httpStatus()).isEqualTo(200);
    }

    @Test
    void rejectsLegacyAdditionalFieldsAndNonNullDetectionFields() {
        assertInvalid(legacySnapshot().put("additionalField", "rejected"));
        assertInvalid(legacySnapshot().put("riskLevel", "HIGH"));
        assertInvalid(legacySnapshot().put("riskResponseOutcome", "HELD"));
        assertInvalid(legacySnapshot().put(
                "adoptedDetectionResultId",
                "det_ref"
        ));
        assertInvalid(legacySnapshot().put("caseId", "case_ref"));
    }

    @Test
    void rejectsLegacyMissingWrongTypedAndInvalidValueFields() {
        assertInvalid(legacySnapshot().remove("createdAt"));
        assertInvalid(legacySnapshot().put("transactionId", 1));
        assertInvalid(legacySnapshot().put(
                "transactionId",
                "not-a-canonical-uuid-v4"
        ));
        assertInvalid(legacySnapshot().put("processingStatus", "ANALYZED"));
        assertInvalid(legacySnapshot().put("createdAt", "not-an-instant"));
        assertInvalid(legacySnapshot().put(
                "createdAt",
                "2026-07-23T10:15:31.123456+09:00"
        ));
    }

    @Test
    void rejectsEnvelopeAndResponseBodyAdditionalFields() {
        ObjectNode envelope = envelope();
        envelope.put("additionalEnvelopeField", "rejected");
        assertInvalid(envelope);

        ObjectNode bodyWithExtra = envelope();
        ((ObjectNode) bodyWithExtra.get("responseBody"))
                .put("additionalBodyField", "rejected");
        assertInvalid(bodyWithExtra);
    }

    @Test
    void rejectsUnknownSchemaAndCodecVersions() {
        assertInvalid(envelope().put(
                "responseSchemaVersion",
                "transaction-create-response-v999"
        ));
        assertInvalid(envelope().put(
                "codecVersion",
                "transaction-intake-snapshot-envelope-v999"
        ));
    }

    @Test
    void rejectsEnvelopeMissingFieldsAndWrongJsonTypes() {
        assertEnvelopeMissing("responseBody");
        assertEnvelopeMissing("httpStatus");
        assertEnvelopeMissing("responseSchemaVersion");
        assertEnvelopeMissing("codecVersion");
        assertEnvelopeMissing("finalizedAt");
        assertInvalid(envelope().put("responseBody", "not-an-object"));
        assertInvalid(envelope().put("httpStatus", "201"));
        assertInvalid(envelope().put("responseSchemaVersion", 1));
        assertInvalid(envelope().put("codecVersion", 1));
    }

    @Test
    void rejectsEnvelopeHttpStatusOtherThan201() {
        assertInvalid(envelope().put("httpStatus", 200));
        assertInvalid(envelope().put("httpStatus", 202));
        assertInvalid(envelope().put("httpStatus", 500));
    }

    @Test
    void rejectsFinalizedAtWrongTypeInvalidInstantAndNonUtcForm() {
        assertInvalid(envelope().put("finalizedAt", 1));
        assertInvalid(envelope().put("finalizedAt", "not-an-instant"));
        assertInvalid(envelope().put(
                "finalizedAt",
                "2026-07-23T10:15:33.654321+09:00"
        ));
    }

    @Test
    void rejectsTraceIdAtEveryEnvelopeDepth() {
        assertInvalid(envelope().put("traceId", "top-level-trace"));

        ObjectNode responseBodyTrace = envelope();
        ((ObjectNode) responseBodyTrace.get("responseBody"))
                .put("traceId", "response-body-trace");
        assertInvalid(responseBodyTrace);

        ObjectNode nestedTrace = envelope();
        ((ObjectNode) nestedTrace.get("responseBody")).set(
                "riskLevel",
                objectMapper.createObjectNode()
                        .put("traceId", "nested-object-trace")
        );
        assertInvalid(nestedTrace);
    }

    @Test
    void doesNotFallbackToLegacyAfterEnvelopeCandidateDecodeFailure()
            throws Exception {
        TransactionIntakeLegacySnapshotCodec legacySpy = spy(legacyCodec);
        TransactionIntakeSnapshotCodec dispatchingCodec =
                new TransactionIntakeSnapshotCodec(
                        objectMapper,
                        legacySpy,
                        envelopeCodec,
                        envelopeV2Codec
                );
        ObjectNode envelopeCandidate = legacySnapshot();
        envelopeCandidate.put(
                "codecVersion",
                "transaction-intake-snapshot-envelope-v1"
        );

        assertThatThrownBy(() -> dispatchingCodec.decode(
                objectMapper.writeValueAsString(envelopeCandidate)
        )).isInstanceOf(InvalidTransactionIntakeSnapshotException.class);

        verify(legacySpy, never()).decode(any());
    }

    @Test
    void rejectsMixedUnknownAndMissingVersionTuplesWithoutLegacyFallback() {
        assertInvalid(envelope().put(
                "codecVersion",
                "transaction-intake-snapshot-envelope-v2"
        ));
        assertInvalid(envelope().put(
                "responseSchemaVersion",
                "transaction-create-response-v2"
        ));
        assertInvalid(envelope().remove("responseSchemaVersion"));
        assertInvalid(envelope().remove("codecVersion"));
        assertInvalid(envelope().putNull("responseSchemaVersion"));
        assertInvalid(envelope().put("codecVersion", 2));
    }

    @Test
    void rejectsMalformedAndUnknownShapesWithoutRawContent() {
        String sensitiveRaw =
                "{\"traceId\":\"secret_trace\",\"account\":\"secret_account\"}";
        assertInvalidRaw(sensitiveRaw);
        assertInvalidRaw("{");
        assertInvalidRaw("{} {}");
        assertInvalidRaw("[]");
        assertInvalidRaw("{\"unknown\":\"shape\"}");
    }

    private ObjectNode envelope() {
        return (ObjectNode) codec.encode(snapshot(), 201, FINALIZED_AT);
    }

    private ObjectNode legacySnapshot() {
        ObjectNode legacy = objectMapper.createObjectNode();
        legacy.put("transactionId", TRANSACTION_ID.toString());
        legacy.put("processingStatus", "RECEIVED");
        legacy.putNull("riskLevel");
        legacy.putNull("riskResponseOutcome");
        legacy.putNull("adoptedDetectionResultId");
        legacy.putNull("caseId");
        legacy.put(
                "createdAt",
                "2026-07-23T01:15:31.123456Z"
        );
        return legacy;
    }

    private void assertInvalid(JsonNode rawSnapshot) {
        assertInvalidRaw(rawSnapshot.toString());
    }

    private void assertEnvelopeMissing(String field) {
        ObjectNode envelope = envelope();
        envelope.remove(field);
        assertInvalid(envelope);
    }

    private void assertInvalidRaw(String rawSnapshot) {
        assertThatThrownBy(() -> codec.decode(rawSnapshot))
                .isInstanceOf(InvalidTransactionIntakeSnapshotException.class)
                .hasMessage("Stored transaction intake snapshot is invalid")
                .hasMessageNotContaining(rawSnapshot)
                .hasMessageNotContaining("secret");
    }

    private TransactionIntakeSnapshot snapshot() {
        return new TransactionIntakeSnapshot(
                TRANSACTION_ID,
                TransactionProcessingStatus.RECEIVED,
                null,
                null,
                null,
                null,
                CREATED_AT
        );
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private boolean containsFieldRecursively(JsonNode node, String fieldName) {
        if (node.isObject()) {
            if (node.has(fieldName)) {
                return true;
            }
            for (JsonNode value : node) {
                if (containsFieldRecursively(value, fieldName)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsFieldRecursively(value, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
