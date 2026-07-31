package com.aifds.backend.detection.entity;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectionDomainTest {

    private static final Instant CUTOFF =
            Instant.parse("2026-07-30T01:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsUuidV4PendingAndMovesThroughCompletedState() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = pending(transaction, 1);

        assertThat(result.getDetectionResultId().version()).isEqualTo(4);
        assertThat(result.getDetectionResultId().variant()).isEqualTo(2);
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.PENDING);

        result.start(CUTOFF.plusSeconds(1));
        result.complete(55, RiskLevel.HIGH, CUTOFF.plusSeconds(2));

        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.COMPLETED);
        assertThat(result.getRiskScore()).isEqualTo(55);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThatThrownBy(
                () -> result.fail("LATE_FAILURE", CUTOFF.plusSeconds(3))
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedResultKeepsItsVersionAndIsTerminal() {
        DetectionResult result = pending(transaction(UUID.randomUUID()), 3);

        result.fail("DEPENDENCY_TIMEOUT", CUTOFF.plusSeconds(1));

        assertThat(result.getDetectionResultVersion()).isEqualTo(3);
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThatThrownBy(() -> result.start(CUTOFF.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesScoreAndStateTransitions() {
        DetectionResult pending = pending(transaction(UUID.randomUUID()), 1);

        assertThatThrownBy(
                () -> pending.complete(10, RiskLevel.LOW, CUTOFF)
        ).isInstanceOf(IllegalStateException.class);

        pending.start(CUTOFF.plusSeconds(1));
        assertThatThrownBy(
                () -> pending.complete(101, RiskLevel.HIGH, CUTOFF.plusSeconds(2))
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> pending.complete(10, RiskLevel.LOW, CUTOFF)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsImmutableRuleEvidenceWithUuidV4AndDefensiveJson() {
        DetectionResult result = pending(transaction(UUID.randomUUID()), 1);
        ObjectNode summary = amountSummary();
        RuleVersion version = publishedAmountRuleVersion();

        DetectionEvidence evidence = DetectionEvidence.rule(
                result,
                "고액 이체 기준 이상입니다.",
                version,
                RuleEvidenceObservationSummary.from(
                        version.getReasonCode(),
                        summary,
                        result.getEvaluationCutoffAt()
                ),
                CUTOFF,
                0
        );
        summary.put("observedAmount", "1");

        assertThat(evidence.getEvidenceId().version()).isEqualTo(4);
        assertThat(evidence.getEvidenceId().variant()).isEqualTo(2);
        assertThat(evidence.getEvidenceType())
                .isEqualTo(DetectionEvidenceType.RULE);
        assertThat(evidence.getObservationSummary()
                .get("observedAmount").textValue()).isEqualTo("10000000");
        assertThat(evidence.getRuleCode())
                .isEqualTo(version.getFraudRule().getRuleCode());
        assertThat(evidence.getReasonCode()).isEqualTo(version.getReasonCode());
        assertThat(evidence.getRuleVersion()).isEqualTo("1");
        assertThat(evidence.getScoreContribution()).isEqualTo(15);
    }

    @Test
    void rejectsRuleEvidenceForDraftVersion() {
        DetectionResult result = pending(transaction(UUID.randomUUID()), 1);
        FraudRule rule = FraudRule.create(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                "절대 고액 이체",
                "현재 거래의 절대 금액을 평가한다."
        );
        RuleVersion draft = RuleVersion.draft(
                rule,
                1,
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition(),
                CUTOFF,
                null
        );

        assertThatThrownBy(() -> DetectionEvidence.rule(
                result,
                "고액 이체 기준 이상입니다.",
                draft,
                RuleEvidenceObservationSummary.from(
                        draft.getReasonCode(),
                        amountSummary(),
                        result.getEvaluationCutoffAt()
                ),
                CUTOFF,
                0
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adoptsOnlyCompletedResultFromSameTransactionAndMapsOutcome() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = pending(transaction, 1);
        result.start(CUTOFF.plusSeconds(1));
        result.complete(55, RiskLevel.HIGH, CUTOFF.plusSeconds(2));

        transaction.adoptDetectionResult(result);
        transaction.applyRiskResponseOutcome(
                RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
        );

        assertThat(transaction.getAdoptedDetectionResult()).isSameAs(result);
        assertThat(transaction.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(transaction.getRiskResponseOutcome())
                .isEqualTo(RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED);
        assertThatThrownBy(
                () -> transaction.applyRiskResponseOutcome(
                        RiskResponseOutcome.APPROVED
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPendingOrDifferentTransactionAdoption() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        FinancialTransaction other = transaction(UUID.randomUUID());
        DetectionResult pending = pending(transaction, 1);

        assertThatThrownBy(() -> transaction.adoptDetectionResult(pending))
                .isInstanceOf(IllegalArgumentException.class);

        pending.start(CUTOFF.plusSeconds(1));
        pending.complete(10, RiskLevel.LOW, CUTOFF.plusSeconds(2));
        assertThatThrownBy(() -> other.adoptDetectionResult(pending))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DetectionResult pending(
            FinancialTransaction transaction,
            int version
    ) {
        return DetectionResult.pending(
                transaction,
                version,
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_detection_01"
        );
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                CUTOFF,
                "cust_ref_detection_test",
                "acct_ref_detection_sender",
                "acct_ref_detection_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_detection"
        );
    }

    private ObjectNode amountSummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000");
    }

    private RuleVersion publishedAmountRuleVersion() {
        FraudRule rule = FraudRule.create(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                "절대 고액 이체",
                "현재 거래의 절대 금액을 평가한다."
        );
        RuleVersion version = RuleVersion.draft(
                rule,
                1,
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition(),
                CUTOFF.minusSeconds(60),
                null
        );
        version.publish(CUTOFF.minusSeconds(120));
        return version;
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }
}
