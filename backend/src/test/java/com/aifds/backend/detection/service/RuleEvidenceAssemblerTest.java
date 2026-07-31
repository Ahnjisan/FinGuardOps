package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.exception.RuleEvidenceContractViolationException;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEvidenceAssemblerTest {

    private static final Instant CUTOFF =
            Instant.parse("2026-07-30T01:01:00Z");
    private static final String EVENT_ID =
            "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_EVENT_ID =
            "22222222-2222-4222-8222-222222222222";
    private static final long WINDOW_SECONDS = 60;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assemblesValidR001ThroughR004Evidence() {
        assertAssembled(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountSummary(),
                CUTOFF
        );
        assertAssembled(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceSummary(),
                CUTOFF.minusSeconds(60)
        );
        assertAssembled(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary(),
                CUTOFF.minusSeconds(60)
        );
        assertAssembled(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                beneficiarySummary(),
                CUTOFF.minusSeconds(60)
        );
    }

    @Test
    void rejectsEvidenceWindowThatDiffersFromRuleVersion() {
        RuleVersion version = publishedVersion(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
        );
        RuleEvidenceDraft draft = draft(
                version,
                deviceSummary()
                        .put(
                                "deviceRegisteredAt",
                                CUTOFF.minusSeconds(30).toString()
                        )
                        .put("elapsedSeconds", 30)
                        .put("windowSeconds", 30),
                CUTOFF.minusSeconds(30)
        );

        assertThatThrownBy(() -> RuleEvidenceAssembler.assemble(
                result(),
                version,
                draft
        ))
                .isInstanceOf(RuleEvidenceContractViolationException.class)
                .hasMessageContaining("windowSeconds")
                .hasMessageContaining(
                        RuleV1ContractRegistry
                                .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
                );
    }

    @Test
    void rejectsRuleSpecificEvidenceOccurredAtMismatches() {
        assertWrongEvidenceOccurredAt(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                amountSummary(),
                CUTOFF.plusSeconds(1)
        );
        assertWrongEvidenceOccurredAt(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                deviceSummary(),
                CUTOFF.minusSeconds(59)
        );
        assertWrongEvidenceOccurredAt(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                securitySummary(),
                CUTOFF.minusSeconds(59)
        );
        assertWrongEvidenceOccurredAt(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                beneficiarySummary(),
                CUTOFF.minusSeconds(59)
        );
    }

    private void assertAssembled(
            String ruleCode,
            ObjectNode summary,
            Instant evidenceOccurredAt
    ) {
        RuleVersion version = publishedVersion(ruleCode);
        DetectionEvidence evidence = RuleEvidenceAssembler.assemble(
                result(),
                version,
                draft(version, summary, evidenceOccurredAt)
        );

        assertThat(evidence.getRuleCode()).isEqualTo(ruleCode);
        assertThat(evidence.getReasonCode()).isEqualTo(ruleCode);
        assertThat(evidence.getEvidenceOccurredAt())
                .isEqualTo(evidenceOccurredAt);
    }

    private void assertWrongEvidenceOccurredAt(
            String ruleCode,
            ObjectNode summary,
            Instant wrongEvidenceOccurredAt
    ) {
        RuleVersion version = publishedVersion(ruleCode);

        assertThatThrownBy(() -> RuleEvidenceAssembler.assemble(
                result(),
                version,
                draft(version, summary, wrongEvidenceOccurredAt)
        ))
                .isInstanceOf(RuleEvidenceContractViolationException.class)
                .hasMessageContaining("evidenceOccurredAt")
                .hasMessageContaining(ruleCode);
    }

    private RuleEvidenceDraft draft(
            RuleVersion version,
            ObjectNode summary,
            Instant evidenceOccurredAt
    ) {
        return new RuleEvidenceDraft(
                version.getRuleVersionId(),
                "Rule v1 detection evidence",
                summary,
                evidenceOccurredAt,
                0
        );
    }

    private DetectionResult result() {
        FinancialTransaction transaction = new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                CUTOFF,
                "customer_ref",
                "sender_ref",
                "recipient_ref",
                TransactionChannel.MOBILE_BANKING,
                "device_ref"
        );
        return DetectionResult.pending(
                transaction,
                1,
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_rule_evidence"
        );
    }

    private RuleVersion publishedVersion(String ruleCode) {
        FraudRule rule = FraudRule.create(
                ruleCode,
                "Rule v1 test rule",
                "Rule v1 evidence contract test rule"
        );
        RuleVersion version = RuleVersion.draft(
                rule,
                1,
                ruleCode,
                10,
                conditionDefinition(ruleCode),
                CUTOFF.minusSeconds(120),
                null
        );
        version.publish(CUTOFF.minusSeconds(180));
        return version;
    }

    private ObjectNode conditionDefinition(String ruleCode) {
        return switch (ruleCode) {
            case RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT ->
                    amountCondition();
            case RuleV1ContractRegistry
                    .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT ->
                    objectMapper.createObjectNode()
                            .put(
                                    "prerequisiteRuleCode",
                                    RuleV1ContractRegistry
                                            .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                            )
                            .put("eventType", "DEVICE_REGISTERED")
                            .put("windowSeconds", WINDOW_SECONDS)
                            .put(
                                    "matchPolicy",
                                    "SAME_CUSTOMER_AND_DEVICE"
                            )
                            .put(
                                    "selectionPolicy",
                                    "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                            );
            case RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT ->
                    objectMapper.createObjectNode()
                            .put(
                                    "prerequisiteRuleCode",
                                    RuleV1ContractRegistry
                                            .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                            )
                            .put("passwordEventType", "PASSWORD_CHANGED")
                            .put(
                                    "transferLimitEventType",
                                    "TRANSFER_LIMIT_CHANGED"
                            )
                            .put("windowSeconds", WINDOW_SECONDS)
                            .put(
                                    "matchPolicy",
                                    "SAME_CUSTOMER_AND_SENDER_ACCOUNT"
                            )
                            .put(
                                    "sequencePolicy",
                                    "PASSWORD_CHANGED_AT_OR_BEFORE_"
                                            + "TRANSFER_LIMIT_CHANGED"
                            )
                            .put(
                                    "selectionPolicy",
                                    "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_"
                                            + "LATEST_PASSWORD_THEN_EVENT_ID_ASC"
                            );
            case RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER ->
                    objectMapper.createObjectNode()
                            .put("eventType", "BENEFICIARY_REGISTERED")
                            .put("windowSeconds", WINDOW_SECONDS)
                            .put(
                                    "matchPolicy",
                                    "SAME_CUSTOMER_SENDER_ACCOUNT_"
                                            + "AND_BENEFICIARY"
                            )
                            .put(
                                    "selectionPolicy",
                                    "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                            );
            default -> throw new IllegalArgumentException(
                    "Unsupported test ruleCode: " + ruleCode
            );
        };
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
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
                .put(
                        "deviceRegisteredAt",
                        CUTOFF.minusSeconds(60).toString()
                )
                .put("elapsedSeconds", 60)
                .put("windowSeconds", WINDOW_SECONDS);
    }

    private ObjectNode securitySummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000")
                .put("passwordChangedEventId", EVENT_ID)
                .put(
                        "passwordChangedAt",
                        CUTOFF.minusSeconds(60).toString()
                )
                .put("transferLimitChangedEventId", SECOND_EVENT_ID)
                .put(
                        "transferLimitChangedAt",
                        CUTOFF.minusSeconds(60).toString()
                )
                .put("elapsedSeconds", 60)
                .put("windowSeconds", WINDOW_SECONDS);
    }

    private ObjectNode beneficiarySummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "1000")
                .put("eventId", EVENT_ID)
                .put(
                        "beneficiaryRegisteredAt",
                        CUTOFF.minusSeconds(60).toString()
                )
                .put("elapsedSeconds", 60)
                .put("windowSeconds", WINDOW_SECONDS);
    }
}
