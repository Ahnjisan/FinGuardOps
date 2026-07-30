package com.aifds.backend.detection.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEvidenceObservationSummaryTest {

    private static final Instant EVALUATION_CUTOFF =
            Instant.parse("2026-07-30T01:01:00Z");
    private static final String EVENT_ID =
            "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_EVENT_ID =
            "22222222-2222-4222-8222-222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsEachExactRuleV1Allowlist() {
        assertAccepted(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountSummary()
        );
        assertAccepted(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceSummary()
        );
        assertAccepted(
                RuleEvidenceObservationSummary
                        .RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary()
        );
        assertAccepted(
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                beneficiarySummary()
        );
    }

    @Test
    void requiresBehaviorEventIdsAndRejectsThemForNonBehaviorReason() {
        ObjectNode missingDeviceEventId = deviceSummary();
        missingDeviceEventId.remove("eventId");
        assertInvalid(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                missingDeviceEventId
        );

        ObjectNode missingBeneficiaryEventId = beneficiarySummary();
        missingBeneficiaryEventId.remove("eventId");
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                missingBeneficiaryEventId
        );

        ObjectNode missingPasswordEventId = securitySummary();
        missingPasswordEventId.remove("passwordChangedEventId");
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                missingPasswordEventId
        );

        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountSummary().put("eventId", EVENT_ID)
        );
    }

    @Test
    void requiresCanonicalLowercaseUuidV4BehaviorEventIds() {
        assertInvalidEventId("11111111-1111-1111-8111-111111111111");
        assertInvalidEventId("11111111-1111-4111-4111-111111111111");
        assertInvalidEventId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
        assertInvalidEventId("not-a-uuid");

        ObjectNode invalidSecondId = securitySummary()
                .put(
                        "transferLimitChangedEventId",
                        "22222222-2222-1222-8222-222222222222"
                );
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                invalidSecondId
        );
    }

    @Test
    void enforcesSecurityEventOrderCutoffElapsedSecondsAndWindow() {
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary()
                        .put("passwordChangedAt", "2026-07-30T01:00:01Z")
                        .put("transferLimitChangedAt", "2026-07-30T01:00:00Z")
        );
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary()
                        .put(
                                "transferLimitChangedAt",
                                "2026-07-30T01:01:01Z"
                        )
                        .put("elapsedSeconds", 0)
        );
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary().put("elapsedSeconds", 59)
        );
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary()
                        .put("passwordChangedAt", "2026-07-29T00:00:00Z")
                        .put("windowSeconds", 86400)
        );
    }

    @Test
    void rejectsBehaviorTimesAfterEvaluationCutoff() {
        assertInvalid(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceSummary().put(
                        "deviceRegisteredAt",
                        "2026-07-30T01:01:01Z"
                )
        );
        assertInvalid(
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                beneficiarySummary().put(
                        "beneficiaryRegisteredAt",
                        "2026-07-30T01:01:01Z"
                )
        );
    }

    @Test
    void rejectsUnknownMissingNestedAndNullFields() {
        ObjectNode unknown = amountSummary().put("customerId", "forbidden");
        ObjectNode missing = objectMapper.createObjectNode()
                .put("observedAmount", "10000000");
        ObjectNode nested = amountSummary();
        nested.set(
                "observedAmount",
                objectMapper.createObjectNode().put("value", "10000000")
        );
        ObjectNode nullValue = amountSummary();
        nullValue.putNull("observedAmount");

        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                unknown
        );
        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                missing
        );
        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                nested
        );
        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                nullValue
        );
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createArrayNode().add("10000000"),
                EVALUATION_CUTOFF
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createObjectNode(),
                EVALUATION_CUTOFF
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAmountsTimesAndDurations() {
        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountSummary().put("observedAmount", 10000000)
        );
        assertInvalid(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountSummary().put("observedAmount", "-1")
        );
        assertInvalid(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceSummary()
                        .put(
                                "deviceRegisteredAt",
                                "2026-07-30T10:00:00+09:00"
                        )
                        .put("elapsedSeconds", -1)
        );
    }

    @Test
    void returnsDefensiveJsonCopies() {
        ObjectNode input = amountSummary();
        RuleEvidenceObservationSummary summary =
                RuleEvidenceObservationSummary.from(
                        RuleEvidenceObservationSummary
                                .TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        input,
                        EVALUATION_CUTOFF
                );

        input.put("observedAmount", "1");
        ObjectNode first = (ObjectNode) summary.toJson();
        first.put("observedAmount", "2");

        assertThat(summary.toJson().get("observedAmount").textValue())
                .isEqualTo("10000000");
    }

    private ObjectNode amountSummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000");
    }

    private ObjectNode deviceSummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000")
                .put("eventId", EVENT_ID)
                .put("deviceRegisteredAt", "2026-07-30T01:00:00Z")
                .put("elapsedSeconds", 60)
                .put("windowSeconds", 86400);
    }

    private ObjectNode securitySummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000")
                .put("passwordChangedEventId", EVENT_ID)
                .put("passwordChangedAt", "2026-07-30T00:55:00Z")
                .put("transferLimitChangedEventId", SECOND_EVENT_ID)
                .put("transferLimitChangedAt", "2026-07-30T01:00:00Z")
                .put("elapsedSeconds", 60)
                .put("windowSeconds", 86400);
    }

    private ObjectNode beneficiarySummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "1000")
                .put("eventId", EVENT_ID)
                .put("beneficiaryRegisteredAt", "2026-07-30T01:00:00Z")
                .put("elapsedSeconds", 60)
                .put("windowSeconds", 86400);
    }

    private void assertInvalidEventId(String eventId) {
        assertInvalid(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceSummary().put("eventId", eventId)
        );
    }

    private void assertAccepted(String reasonCode, ObjectNode summary) {
        assertThat(RuleEvidenceObservationSummary.from(
                reasonCode,
                summary,
                EVALUATION_CUTOFF
        ).toJson()).isEqualTo(summary);
    }

    private void assertInvalid(String reasonCode, ObjectNode summary) {
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                reasonCode,
                summary,
                EVALUATION_CUTOFF
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
