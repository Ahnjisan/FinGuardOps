package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionIntakeSnapshotCodecTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-23T01:15:31.123456Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionIntakeSnapshotCodec codec =
            new TransactionIntakeSnapshotCodec(objectMapper);

    @Test
    void encodesExactlySevenBusinessFieldsWithExplicitNulls() {
        JsonNode encoded = codec.encode(snapshot());

        assertThat(toFieldSet(encoded)).containsExactlyInAnyOrder(
                "transactionId",
                "processingStatus",
                "riskLevel",
                "riskResponseOutcome",
                "adoptedDetectionResultId",
                "caseId",
                "createdAt"
        );
        assertThat(encoded.get("transactionId").textValue())
                .isEqualTo(TRANSACTION_ID.toString());
        assertThat(encoded.get("processingStatus").textValue())
                .isEqualTo("RECEIVED");
        assertThat(encoded.get("riskLevel").isNull()).isTrue();
        assertThat(encoded.get("riskResponseOutcome").isNull()).isTrue();
        assertThat(encoded.get("adoptedDetectionResultId").isNull()).isTrue();
        assertThat(encoded.get("caseId").isNull()).isTrue();
        assertThat(encoded.get("createdAt").textValue())
                .isEqualTo("2026-07-23T01:15:31.123456Z");
        assertThat(encoded.has("traceId")).isFalse();
        assertThat(encoded.has("idempotencyRecordId")).isFalse();
        assertThat(encoded.has("fingerprint")).isFalse();
    }

    @Test
    void roundTripsAValidatedTypedSnapshot() throws Exception {
        String encoded = objectMapper.writeValueAsString(
                codec.encode(snapshot())
        );

        assertThat(codec.decode(encoded)).isEqualTo(snapshot());
    }

    @Test
    void decodesNullableStringFieldsWhenTheyArePresent() {
        String encoded = """
                {
                  "transactionId":"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
                  "processingStatus":"RECEIVED",
                  "riskLevel":"HIGH",
                  "riskResponseOutcome":"HELD",
                  "adoptedDetectionResultId":"det_ref_1",
                  "caseId":"case_ref_1",
                  "createdAt":"2026-07-23T01:15:31Z"
                }
                """;

        TransactionIntakeSnapshot decoded = codec.decode(encoded);

        assertThat(decoded.riskLevel()).isEqualTo("HIGH");
        assertThat(decoded.riskResponseOutcome()).isEqualTo("HELD");
        assertThat(decoded.adoptedDetectionResultId())
                .isEqualTo("det_ref_1");
        assertThat(decoded.caseId()).isEqualTo("case_ref_1");
    }

    @Test
    void rejectsMalformedMissingExtraOrWrongTypedSnapshotsWithoutRawContent() {
        String sensitiveRaw =
                "{\"traceId\":\"secret_trace\",\"account\":\"secret_account\"}";
        assertInvalid(sensitiveRaw);
        assertInvalid("[]");
        assertInvalid("""
                {
                  "transactionId":"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
                  "processingStatus":"RECEIVED",
                  "riskLevel":null,
                  "riskResponseOutcome":null,
                  "adoptedDetectionResultId":null,
                  "caseId":null
                }
                """);
        assertInvalid("""
                {
                  "transactionId":"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
                  "processingStatus":"RECEIVED",
                  "riskLevel":null,
                  "riskResponseOutcome":null,
                  "adoptedDetectionResultId":null,
                  "caseId":null,
                  "createdAt":"2026-07-23T01:15:31Z",
                  "traceId":"must-not-be-stored"
                }
                """);
        assertInvalid("""
                {
                  "transactionId":"not-a-uuid",
                  "processingStatus":"RECEIVED",
                  "riskLevel":null,
                  "riskResponseOutcome":null,
                  "adoptedDetectionResultId":null,
                  "caseId":null,
                  "createdAt":"2026-07-23T01:15:31Z"
                }
                """);
        assertInvalid("""
                {
                  "transactionId":"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
                  "processingStatus":"ANALYZED",
                  "riskLevel":null,
                  "riskResponseOutcome":null,
                  "adoptedDetectionResultId":null,
                  "caseId":null,
                  "createdAt":"not-an-instant"
                }
                """);
    }

    private void assertInvalid(String rawSnapshot) {
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

    private Set<String> toFieldSet(JsonNode node) {
        Set<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
