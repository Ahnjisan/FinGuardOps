package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.exception.InvalidExternalRiskFailureSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalRiskFailureSnapshotCodecTest {

    private static final Instant FINALIZED_AT =
            Instant.parse("2026-08-27T00:00:00.123456Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExternalRiskFailureSnapshotCodec codec =
            new ExternalRiskFailureSnapshotCodec(objectMapper);

    @Test
    void roundTripsAllCategoryMappingsWithExactSchemaAndUtcTimestamp() {
        for (ExternalRiskFailureCategory category
                : ExternalRiskFailureCategory.values()) {
            ExternalRiskFailureSnapshot expected =
                    ExternalRiskFailureSnapshot.from(category, FINALIZED_AT);

            JsonNode encoded = codec.encode(expected);
            ExternalRiskFailureSnapshot decoded = codec.decode(
                    encoded.toString(),
                    expected.responseBody().code(),
                    FINALIZED_AT
            );

            assertThat(decoded).isEqualTo(expected);
            assertThat(fieldNames(encoded)).containsExactlyInAnyOrder(
                    "snapshotType",
                    "responseBody",
                    "httpStatus",
                    "failureCategory",
                    "responseSchemaVersion",
                    "codecVersion",
                    "finalizedAt"
            );
            assertThat(fieldNames(encoded.get("responseBody")))
                    .containsExactlyInAnyOrder(
                            "code",
                            "message",
                            "fieldErrors"
                    );
            assertThat(encoded.get("responseBody").get("fieldErrors"))
                    .isEmpty();
            assertThat(encoded.get("finalizedAt").textValue()).endsWith("Z");
        }
    }

    @Test
    void rejectsMissingAdditionalUnknownVersionTypeAndTrailingToken() {
        ObjectNode valid = validTimeout();

        assertInvalid(valid.deepCopy().without("codecVersion").toString());
        assertInvalid(valid.deepCopy().put("unexpected", true).toString());
        assertInvalid(valid.deepCopy().put("snapshotType", "unknown").toString());
        assertInvalid(valid.deepCopy().put("codecVersion", "v2").toString());
        assertInvalid(valid.deepCopy().put(
                "responseSchemaVersion",
                "transaction-create-error-v2"
        ).toString());
        assertInvalid(valid + " {}");
        assertInvalid("[]");
    }

    @Test
    void rejectsWrongTypesUnknownCategoryAndNonEmptyFieldErrors() {
        ObjectNode valid = validTimeout();

        assertInvalid(valid.deepCopy().put("httpStatus", "503").toString());
        assertInvalid(valid.deepCopy().put("failureCategory", "UNKNOWN").toString());
        ObjectNode nonEmpty = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) nonEmpty
                .get("responseBody").get("fieldErrors")).add("unsafe");
        assertInvalid(nonEmpty.toString());
    }

    @Test
    void rejectsCodeFinishedAtMappingAndUtcViolations() {
        ObjectNode valid = validTimeout();

        assertThatThrownBy(() -> codec.decode(
                valid.toString(),
                "DEPENDENCY_UNAVAILABLE",
                FINALIZED_AT
        )).isInstanceOf(InvalidExternalRiskFailureSnapshotException.class);
        assertThatThrownBy(() -> codec.decode(
                valid.toString(),
                "DEPENDENCY_TIMEOUT",
                FINALIZED_AT.plusSeconds(1)
        )).isInstanceOf(InvalidExternalRiskFailureSnapshotException.class);
        assertInvalid(valid.deepCopy().put(
                "finalizedAt",
                "2026-08-27T09:00:00+09:00"
        ).toString());
        assertInvalid(valid.deepCopy().put("httpStatus", 500).toString());
    }

    @Test
    void acceptsExactCanonicalFourKibBoundaryAndRejectsOneByteExcess() {
        JsonNode encoded = codec.encode(ExternalRiskFailureSnapshot.from(
                ExternalRiskFailureCategory.TIMEOUT,
                FINALIZED_AT
        ));
        int encodedBytes = encoded.toString().getBytes(StandardCharsets.UTF_8).length;
        assertThat(encodedBytes)
                .isLessThanOrEqualTo(
                        ExternalRiskFailureSnapshotCodec.MAX_CANONICAL_UTF8_BYTES
                );

        ObjectNode boundary = objectMapper.createObjectNode()
                .put("padding", "");
        int emptyBytes = canonicalBytes(boundary);
        boundary.put(
                "padding",
                "a".repeat(
                        ExternalRiskFailureSnapshotCodec.MAX_CANONICAL_UTF8_BYTES
                                - emptyBytes
                )
        );
        assertThat(canonicalBytes(boundary)).isEqualTo(
                ExternalRiskFailureSnapshotCodec.MAX_CANONICAL_UTF8_BYTES
        );
        assertThatCode(() -> codec.requireCanonicalSize(boundary))
                .doesNotThrowAnyException();

        boundary.put(
                "padding",
                boundary.get("padding").textValue() + "a"
        );
        assertThat(canonicalBytes(boundary)).isEqualTo(
                ExternalRiskFailureSnapshotCodec.MAX_CANONICAL_UTF8_BYTES + 1
        );
        assertThatThrownBy(() -> codec.requireCanonicalSize(boundary))
                .isInstanceOf(InvalidExternalRiskFailureSnapshotException.class);
    }

    @Test
    void rejectsTraceAndSensitiveFieldsWithoutExposingStoredValues() {
        String sentinel = "SENTINEL_SECRET_ACCOUNT_REFERENCE";
        ObjectNode trace = validTimeout().put("traceId", sentinel);
        ObjectNode transaction = validTimeout().put("transactionId", sentinel);
        ObjectNode sensitiveMessage = validTimeout();
        sensitiveMessage.withObject("responseBody").put("message", sentinel);

        for (JsonNode unsafe : List.of(trace, transaction, sensitiveMessage)) {
            assertThatThrownBy(() -> codec.decode(
                    unsafe.toString(),
                    "DEPENDENCY_TIMEOUT",
                    FINALIZED_AT
            )).isInstanceOf(InvalidExternalRiskFailureSnapshotException.class)
                    .hasMessage("Stored External Risk failure snapshot is invalid")
                    .hasMessageNotContaining(sentinel)
                    .satisfies(exception -> assertThat(exception.getCause()).isNull());
        }
    }

    @Test
    void encodedTreeIsIndependentAcrossCalls() {
        ExternalRiskFailureSnapshot snapshot = ExternalRiskFailureSnapshot.from(
                ExternalRiskFailureCategory.TIMEOUT,
                FINALIZED_AT
        );
        ObjectNode first = (ObjectNode) codec.encode(snapshot);
        first.put("mutated", true);

        assertThat(codec.encode(snapshot).has("mutated")).isFalse();
    }

    private ObjectNode validTimeout() {
        return (ObjectNode) codec.encode(ExternalRiskFailureSnapshot.from(
                ExternalRiskFailureCategory.TIMEOUT,
                FINALIZED_AT
        ));
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> codec.decode(
                json,
                "DEPENDENCY_TIMEOUT",
                FINALIZED_AT
        )).isInstanceOf(InvalidExternalRiskFailureSnapshotException.class);
    }

    private List<String> fieldNames(JsonNode node) {
        return java.util.stream.StreamSupport.stream(
                ((Iterable<String>) () -> node.fieldNames()).spliterator(),
                false
        ).toList();
    }

    private int canonicalBytes(JsonNode node) {
        try {
            return objectMapper.writeValueAsBytes(node).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
