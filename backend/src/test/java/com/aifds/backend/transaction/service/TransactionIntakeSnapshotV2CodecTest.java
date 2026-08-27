package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionIntakeSnapshotV2CodecTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final UUID DETECTION_RESULT_ID = UUID.fromString(
            "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101"
    );
    private static final UUID CASE_ID = UUID.fromString(
            "9f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430201"
    );
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-27T00:00:00.123456Z");
    private static final Instant FINALIZED_AT =
            Instant.parse("2026-08-27T00:00:02.654321Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionIntakeSnapshotBodyCodec v1BodyCodec =
            new TransactionIntakeSnapshotBodyCodec(objectMapper);
    private final TransactionIntakeSnapshotEnvelopeCodec v1EnvelopeCodec =
            new TransactionIntakeSnapshotEnvelopeCodec(
                    objectMapper,
                    v1BodyCodec
            );
    private final TransactionFinalResponseSnapshotBodyCodec bodyCodec =
            new TransactionFinalResponseSnapshotBodyCodec(objectMapper);
    private final TransactionIntakeSnapshotEnvelopeV2Codec envelopeCodec =
            new TransactionIntakeSnapshotEnvelopeV2Codec(
                    objectMapper,
                    bodyCodec
            );
    private final TransactionIntakeSnapshotCodec dispatcher =
            new TransactionIntakeSnapshotCodec(
                    objectMapper,
                    new TransactionIntakeLegacySnapshotCodec(v1BodyCodec),
                    v1EnvelopeCodec,
                    envelopeCodec
            );

    @Test
    void roundTripsAllRiskLevelsWithExactCanonicalSchema() throws Exception {
        for (RiskLevel riskLevel : RiskLevel.values()) {
            TransactionFinalResponseSnapshot snapshot = snapshot(riskLevel);

            JsonNode encoded = dispatcher.encodeV2(
                    snapshot,
                    201,
                    FINALIZED_AT
            );
            String canonical = objectMapper.writer()
                    .without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(encoded);

            assertThat(fieldNames(encoded)).containsExactly(
                    "responseBody",
                    "httpStatus",
                    "responseSchemaVersion",
                    "codecVersion",
                    "finalizedAt"
            );
            assertThat(fieldNames(encoded.get("responseBody"))).containsExactly(
                    "transactionId",
                    "processingStatus",
                    "riskLevel",
                    "riskResponseOutcome",
                    "adoptedDetectionResultId",
                    "caseId",
                    "createdAt"
            );
            assertThat(encoded.has("fieldErrors")).isFalse();
            assertThat(encoded.get("responseBody").has("fieldErrors"))
                    .isFalse();
            assertThat(encoded.get("httpStatus").intValue()).isEqualTo(201);
            assertThat(encoded.get("responseSchemaVersion").textValue())
                    .isEqualTo("transaction-create-response-v2");
            assertThat(encoded.get("codecVersion").textValue())
                    .isEqualTo("transaction-intake-snapshot-envelope-v2");
            assertThat(encoded.get("finalizedAt").textValue())
                    .isEqualTo("2026-08-27T00:00:02.654321Z");
            assertThat(canonical).doesNotContain("\n", "\r", "  ");
            assertThat(dispatcher.decode(canonical))
                    .isEqualTo(new TransactionIntakeSnapshotReplay(
                            snapshot.toTransactionIntakeSnapshot(),
                            201
                    ));
        }
    }

    @Test
    void buildsTypedImmutableSnapshotFromFinalizationResultWithoutLoss() {
        RiskResponseFinalizationResult finalizationResult = finalizationResult(
                RiskLevel.CRITICAL
        );

        TransactionFinalResponseSnapshot snapshot =
                new TransactionFinalResponseSnapshot(
                        finalizationResult,
                        CREATED_AT
                );

        assertThat(TransactionFinalResponseSnapshot.class.isRecord()).isTrue();
        assertThat(snapshot.transactionId())
                .isEqualTo(finalizationResult.transactionId());
        assertThat(snapshot.processingStatus())
                .isEqualTo(finalizationResult.processingStatus());
        assertThat(snapshot.riskLevel())
                .isEqualTo(finalizationResult.riskLevel());
        assertThat(snapshot.riskResponseOutcome())
                .isEqualTo(finalizationResult.riskResponseOutcome());
        assertThat(snapshot.adoptedDetectionResultId())
                .isEqualTo(finalizationResult.adoptedDetectionResultId());
        assertThat(snapshot.caseId()).isEqualTo(finalizationResult.caseId());
        assertThat(snapshot.createdAt()).isEqualTo(CREATED_AT);
        assertThat(snapshot.toTransactionIntakeSnapshot())
                .isEqualTo(new TransactionIntakeSnapshot(
                        TRANSACTION_ID,
                        TransactionProcessingStatus.HELD,
                        "CRITICAL",
                        "HELD",
                        DETECTION_RESULT_ID.toString(),
                        CASE_ID.toString(),
                        CREATED_AT
                ));
    }

    @Test
    void modelAndDecoderRejectEveryInvalidBusinessCombination() {
        for (RiskLevel riskLevel : RiskLevel.values()) {
            for (TransactionProcessingStatus processingStatus
                    : TransactionProcessingStatus.values()) {
                for (RiskResponseOutcome outcome
                        : RiskResponseOutcome.values()) {
                    for (boolean hasCase : List.of(false, true)) {
                        UUID caseId = hasCase ? CASE_ID : null;
                        boolean valid = isOfficialCombination(
                                riskLevel,
                                processingStatus,
                                outcome,
                                caseId
                        );

                        if (valid) {
                            assertThatCode(() -> newSnapshot(
                                    riskLevel,
                                    processingStatus,
                                    outcome,
                                    caseId
                            )).doesNotThrowAnyException();
                        } else {
                            assertThatThrownBy(() -> newSnapshot(
                                    riskLevel,
                                    processingStatus,
                                    outcome,
                                    caseId
                            )).isInstanceOf(IllegalArgumentException.class);
                        }

                        ObjectNode envelope = validEnvelope(RiskLevel.LOW);
                        ObjectNode body = responseBody(envelope);
                        body.put("processingStatus", processingStatus.name());
                        body.put("riskLevel", riskLevel.name());
                        body.put("riskResponseOutcome", outcome.name());
                        if (caseId == null) {
                            body.putNull("caseId");
                        } else {
                            body.put("caseId", caseId.toString());
                        }
                        if (valid) {
                            assertThatCode(() -> decode(envelope))
                                    .doesNotThrowAnyException();
                        } else {
                            assertInvalid(envelope);
                        }
                    }
                }
            }
        }
    }

    @Test
    void rejectsBodyMissingExtraWrongTypeAndNullFields() {
        for (String field : TransactionFinalResponseSnapshotBodyCodec
                .BODY_FIELDS) {
            ObjectNode missing = validEnvelope(RiskLevel.CRITICAL);
            responseBody(missing).remove(field);
            assertInvalid(missing);

            ObjectNode wrongType = validEnvelope(RiskLevel.CRITICAL);
            responseBody(wrongType).putArray(field).add("wrong-type");
            assertInvalid(wrongType);

            ObjectNode nullValue = validEnvelope(RiskLevel.CRITICAL);
            responseBody(nullValue).putNull(field);
            assertInvalid(nullValue);
        }

        ObjectNode extra = validEnvelope(RiskLevel.CRITICAL);
        responseBody(extra).put("unexpected", "rejected");
        assertInvalid(extra);
        assertInvalid(objectMapper.createObjectNode());
    }

    @Test
    void rejectsEnvelopeMissingExtraWrongTypeAndNullFields() {
        for (String field : List.of(
                "responseBody",
                "httpStatus",
                "responseSchemaVersion",
                "codecVersion",
                "finalizedAt"
        )) {
            ObjectNode missing = validEnvelope(RiskLevel.CRITICAL);
            missing.remove(field);
            assertInvalid(missing);

            ObjectNode wrongType = validEnvelope(RiskLevel.CRITICAL);
            wrongType.putArray(field).add("wrong-type");
            assertInvalid(wrongType);

            ObjectNode nullValue = validEnvelope(RiskLevel.CRITICAL);
            nullValue.putNull(field);
            assertInvalid(nullValue);
        }

        assertInvalid(validEnvelope(RiskLevel.CRITICAL)
                .put("unexpected", "rejected"));
    }

    @Test
    void rejectsUuidUtcHttpVersionAndTrailingTokenViolations() {
        ObjectNode uppercaseTransaction = validEnvelope(RiskLevel.CRITICAL);
        responseBody(uppercaseTransaction).put(
                "transactionId",
                TRANSACTION_ID.toString().toUpperCase()
        );
        assertInvalid(uppercaseTransaction);

        ObjectNode nonV4Detection = validEnvelope(RiskLevel.CRITICAL);
        responseBody(nonV4Detection).put(
                "adoptedDetectionResultId",
                "7f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430101"
        );
        assertInvalid(nonV4Detection);
        assertThatThrownBy(() -> new TransactionFinalResponseSnapshot(
                UUID.fromString("2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"),
                TransactionProcessingStatus.APPROVED,
                RiskLevel.LOW,
                RiskResponseOutcome.APPROVED,
                DETECTION_RESULT_ID,
                null,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);

        ObjectNode nonCanonicalCase = validEnvelope(RiskLevel.CRITICAL);
        responseBody(nonCanonicalCase).put("caseId", CASE_ID.toString() + "0");
        assertInvalid(nonCanonicalCase);

        ObjectNode createdAtOffset = validEnvelope(RiskLevel.CRITICAL);
        responseBody(createdAtOffset).put(
                "createdAt",
                "2026-08-27T09:00:00.123456+09:00"
        );
        assertInvalid(createdAtOffset);

        ObjectNode nonCanonicalCreatedAt = validEnvelope(RiskLevel.CRITICAL);
        responseBody(nonCanonicalCreatedAt).put(
                "createdAt",
                "2026-08-27T00:00:00.1234560Z"
        );
        assertInvalid(nonCanonicalCreatedAt);

        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put(
                "finalizedAt",
                "2026-08-27T09:00:02.654321+09:00"
        ));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put(
                "finalizedAt",
                "2026-08-27T00:00:02.6543210Z"
        ));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put("httpStatus", 200));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put("httpStatus", "201"));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put(
                "responseSchemaVersion",
                "transaction-create-response-v1"
        ));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put(
                "codecVersion",
                "transaction-intake-snapshot-envelope-v1"
        ));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).remove("codecVersion"));
        assertInvalid(validEnvelope(RiskLevel.CRITICAL).put(
                "responseSchemaVersion",
                2
        ));
        assertInvalidRaw(validEnvelope(RiskLevel.CRITICAL) + " {}");
    }

    @Test
    void rejectsDuplicateKeysBeforeTreeConversion() {
        String envelope = validEnvelope(RiskLevel.CRITICAL).toString();
        assertInvalidRaw(envelope.replace(
                "\"codecVersion\":\"transaction-intake-snapshot-envelope-v2\"",
                "\"codecVersion\":\"unknown\","
                        + "\"codecVersion\":"
                        + "\"transaction-intake-snapshot-envelope-v2\""
        ));
        assertInvalidRaw(envelope.replace(
                "\"transactionId\":\"" + TRANSACTION_ID + "\"",
                "\"transactionId\":\"" + UUID.randomUUID() + "\","
                        + "\"transactionId\":\"" + TRANSACTION_ID + "\""
        ));
    }

    @Test
    void rejectsNestedTraceSensitiveProviderEntityAndExceptionFields() {
        for (String forbidden : List.of(
                "traceId",
                "accountRef",
                "providerRawResponse",
                "entity",
                "exceptionMessage",
                "stackTrace",
                "authorization"
        )) {
            String sentinel = "SENTINEL_SECRET_VALUE";
            ObjectNode unsafe = validEnvelope(RiskLevel.CRITICAL);
            responseBody(unsafe).set(
                    "riskLevel",
                    objectMapper.createObjectNode().set(
                            "nested",
                            objectMapper.createObjectNode().put(
                                    forbidden,
                                    sentinel
                            )
                    )
            );

            assertThatThrownBy(() -> decode(unsafe))
                    .isInstanceOf(InvalidTransactionIntakeSnapshotException.class)
                    .hasMessage("Stored transaction intake snapshot is invalid")
                    .hasMessageNotContaining(sentinel);
        }

        String encoded = validEnvelope(RiskLevel.CRITICAL).toString();
        assertThat(encoded).doesNotContain(
                "traceId",
                "provider",
                "exception",
                "stackTrace",
                "Entity",
                "fieldErrors"
        );
    }

    @Test
    void validatesCanonicalFourKibBoundaryAndRejectsOneByteExcess()
            throws Exception {
        JsonNode encoded = dispatcher.encodeV2(
                snapshot(RiskLevel.CRITICAL),
                201,
                FINALIZED_AT
        );
        assertThat(canonicalBytes(encoded))
                .isLessThanOrEqualTo(
                        TransactionIntakeSnapshotEnvelopeV2Codec
                                .MAX_CANONICAL_UTF8_BYTES
                );

        ObjectNode boundary = objectMapper.createObjectNode().put("padding", "");
        int emptyBytes = canonicalBytes(boundary);
        boundary.put(
                "padding",
                "a".repeat(
                        TransactionIntakeSnapshotEnvelopeV2Codec
                                .MAX_CANONICAL_UTF8_BYTES - emptyBytes
                )
        );
        assertThat(canonicalBytes(boundary)).isEqualTo(4096);
        assertThatCode(() -> envelopeCodec.requireCanonicalSize(boundary))
                .doesNotThrowAnyException();

        boundary.put(
                "padding",
                boundary.get("padding").textValue() + "a"
        );
        assertThat(canonicalBytes(boundary)).isEqualTo(4097);
        assertThatThrownBy(() -> envelopeCodec.requireCanonicalSize(boundary))
                .isInstanceOf(InvalidTransactionIntakeSnapshotException.class);
    }

    @Test
    void returnsIndependentEncodedTreesAndRejectsFailureSnapshotShape() {
        ObjectNode first = validEnvelope(RiskLevel.HIGH);
        responseBody(first).put("riskLevel", "MUTATED");

        ObjectNode second = validEnvelope(RiskLevel.HIGH);
        assertThat(responseBody(second).get("riskLevel").textValue())
                .isEqualTo("HIGH");

        ObjectNode failureBody = objectMapper.createObjectNode();
        failureBody.put("code", "DEPENDENCY_TIMEOUT");
        failureBody.put("message", "safe");
        failureBody.putArray("fieldErrors");
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("snapshotType", "external-risk-failure");
        failure.set("responseBody", failureBody);
        failure.put("httpStatus", 503);
        failure.put("failureCategory", "TIMEOUT");
        failure.put("responseSchemaVersion", "transaction-create-error-v1");
        failure.put(
                "codecVersion",
                "external-risk-failure-snapshot-envelope-v1"
        );
        failure.put("finalizedAt", FINALIZED_AT.toString());
        assertInvalid(failure);
    }

    private TransactionFinalResponseSnapshot snapshot(RiskLevel riskLevel) {
        return new TransactionFinalResponseSnapshot(
                finalizationResult(riskLevel),
                CREATED_AT
        );
    }

    private RiskResponseFinalizationResult finalizationResult(
            RiskLevel riskLevel
    ) {
        return switch (riskLevel) {
            case LOW -> result(
                    RiskLevel.LOW,
                    TransactionProcessingStatus.APPROVED,
                    RiskResponseOutcome.APPROVED,
                    null,
                    false
            );
            case MEDIUM -> result(
                    RiskLevel.MEDIUM,
                    TransactionProcessingStatus.APPROVED,
                    RiskResponseOutcome.APPROVED_WITH_MONITORING,
                    null,
                    false
            );
            case HIGH -> result(
                    RiskLevel.HIGH,
                    TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                    RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                    CASE_ID,
                    true
            );
            case CRITICAL -> result(
                    RiskLevel.CRITICAL,
                    TransactionProcessingStatus.HELD,
                    RiskResponseOutcome.HELD,
                    CASE_ID,
                    false
            );
        };
    }

    private RiskResponseFinalizationResult result(
            RiskLevel riskLevel,
            TransactionProcessingStatus processingStatus,
            RiskResponseOutcome outcome,
            UUID caseId,
            boolean caseCreated
    ) {
        return new RiskResponseFinalizationResult(
                TRANSACTION_ID,
                DETECTION_RESULT_ID,
                riskLevel,
                processingStatus,
                outcome,
                caseId,
                caseCreated
        );
    }

    private TransactionFinalResponseSnapshot newSnapshot(
            RiskLevel riskLevel,
            TransactionProcessingStatus processingStatus,
            RiskResponseOutcome outcome,
            UUID caseId
    ) {
        return new TransactionFinalResponseSnapshot(
                TRANSACTION_ID,
                processingStatus,
                riskLevel,
                outcome,
                DETECTION_RESULT_ID,
                caseId,
                CREATED_AT
        );
    }

    private boolean isOfficialCombination(
            RiskLevel riskLevel,
            TransactionProcessingStatus processingStatus,
            RiskResponseOutcome outcome,
            UUID caseId
    ) {
        return switch (riskLevel) {
            case LOW -> processingStatus == TransactionProcessingStatus.APPROVED
                    && outcome == RiskResponseOutcome.APPROVED
                    && caseId == null;
            case MEDIUM -> processingStatus == TransactionProcessingStatus.APPROVED
                    && outcome == RiskResponseOutcome.APPROVED_WITH_MONITORING
                    && caseId == null;
            case HIGH -> processingStatus
                    == TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                    && outcome == RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
                    && caseId != null;
            case CRITICAL -> processingStatus == TransactionProcessingStatus.HELD
                    && outcome == RiskResponseOutcome.HELD
                    && caseId != null;
        };
    }

    private ObjectNode validEnvelope(RiskLevel riskLevel) {
        return (ObjectNode) dispatcher.encodeV2(
                snapshot(riskLevel),
                201,
                FINALIZED_AT
        );
    }

    private ObjectNode responseBody(ObjectNode envelope) {
        return (ObjectNode) envelope.get("responseBody");
    }

    private TransactionIntakeSnapshotReplay decode(JsonNode node) {
        return dispatcher.decode(node.toString());
    }

    private void assertInvalid(JsonNode node) {
        assertInvalidRaw(node.toString());
    }

    private void assertInvalidRaw(String json) {
        assertThatThrownBy(() -> dispatcher.decode(json))
                .isInstanceOf(InvalidTransactionIntakeSnapshotException.class)
                .hasMessage("Stored transaction intake snapshot is invalid");
    }

    private List<String> fieldNames(JsonNode node) {
        return java.util.stream.StreamSupport.stream(
                ((Iterable<String>) () -> node.fieldNames()).spliterator(),
                false
        ).toList();
    }

    private int canonicalBytes(JsonNode node) throws Exception {
        return objectMapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsBytes(node)
                .length;
    }
}
