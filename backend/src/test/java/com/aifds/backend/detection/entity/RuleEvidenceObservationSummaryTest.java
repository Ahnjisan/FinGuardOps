package com.aifds.backend.detection.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEvidenceObservationSummaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsEachExactRuleV1Allowlist() {
        assertAccepted(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000")
        );
        assertAccepted(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000")
                        .put("deviceRegisteredAt", "2026-07-30T01:00:00Z")
                        .put("elapsedSeconds", 60)
                        .put("windowSeconds", 86400)
        );
        assertAccepted(
                RuleEvidenceObservationSummary
                        .RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000")
                        .put("securityEventType", "PASSWORD_CHANGED")
                        .put("securityChangedAt", "2026-07-30T01:00:00Z")
                        .put("elapsedSeconds", 60)
                        .put("windowSeconds", 86400)
        );
        assertAccepted(
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                objectMapper.createObjectNode()
                        .put("observedAmount", "1000")
                        .put("beneficiaryRegisteredAt", "2026-07-30T01:00:00Z")
                        .put("elapsedSeconds", 60)
                        .put("windowSeconds", 86400)
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

        assertInvalid(unknown);
        assertInvalid(missing);
        assertInvalid(nested);
        assertInvalid(nullValue);
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createArrayNode().add("10000000")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                objectMapper.createObjectNode()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAmountsTimesDurationsAndSecurityTypes() {
        assertInvalid(amountSummary().put("observedAmount", 10000000));
        assertInvalid(amountSummary().put("observedAmount", "-1"));

        ObjectNode device = objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000")
                .put("deviceRegisteredAt", "2026-07-30T10:00:00+09:00")
                .put("elapsedSeconds", -1)
                .put("windowSeconds", 86400);
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                device
        )).isInstanceOf(IllegalArgumentException.class);

        ObjectNode security = objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000")
                .put("securityEventType", "LOGIN")
                .put("securityChangedAt", "2026-07-30T01:00:00Z")
                .put("elapsedSeconds", 0)
                .put("windowSeconds", 86400);
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary
                        .RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                security
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsDefensiveJsonCopies() {
        ObjectNode input = amountSummary();
        RuleEvidenceObservationSummary summary =
                RuleEvidenceObservationSummary.from(
                        RuleEvidenceObservationSummary
                                .TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        input
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

    private void assertAccepted(String reasonCode, ObjectNode summary) {
        assertThat(RuleEvidenceObservationSummary.from(
                reasonCode,
                summary
        ).toJson()).isEqualTo(summary);
    }

    private void assertInvalid(ObjectNode summary) {
        assertThatThrownBy(() -> RuleEvidenceObservationSummary.from(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                summary
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
