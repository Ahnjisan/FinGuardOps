package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionResultPersistenceServiceTest {

    private static final Instant CUTOFF =
            Instant.parse("2026-07-30T01:01:00Z");

    @Mock
    private FinancialTransactionRepository transactionRepository;
    @Mock
    private DetectionResultRepository resultRepository;
    @Mock
    private DetectionEvidenceRepository evidenceRepository;
    @Mock
    private RuleVersionRepository ruleVersionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DetectionResultPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new DetectionResultPersistenceService(
                transactionRepository,
                resultRepository,
                evidenceRepository,
                ruleVersionRepository
        );
    }

    @Test
    void loadsEachDraftRuleVersionOnceAndCompletesValidEvidence() {
        DetectionResult result = startedResult();
        RuleVersion version = publishedAmountVersion();
        RuleEvidenceDraft draft = new RuleEvidenceDraft(
                version.getRuleVersionId(),
                "Rule v1 detection evidence",
                amountSummary(),
                CUTOFF,
                0
        );
        when(resultRepository.findByDetectionResultIdForUpdate(
                result.getDetectionResultId()
        )).thenReturn(Optional.of(result));
        when(ruleVersionRepository.findByRuleVersionId(
                version.getRuleVersionId()
        )).thenReturn(Optional.of(version));

        service.complete(
                result.getDetectionResultId(),
                15,
                RiskLevel.LOW,
                CUTOFF.plusSeconds(2),
                List.of(draft)
        );

        verify(ruleVersionRepository, times(1))
                .findByRuleVersionId(version.getRuleVersionId());
        verify(evidenceRepository).saveAllAndFlush(any());
        verify(resultRepository).saveAndFlush(result);
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.COMPLETED);
    }

    @Test
    void createsPendingWhenCutoffExactlyMatchesTransactionOccurredAt() {
        FinancialTransaction transaction = stubStoredTransaction();
        when(resultRepository.findMaximumVersionByTransactionPk(1L))
                .thenReturn(0);
        when(resultRepository.saveAndFlush(any(DetectionResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DetectionResult result = service.createPending(
                transaction.getTransactionId(),
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_cutoff_exact"
        );

        assertThat(result.getEvaluationCutoffAt()).isEqualTo(CUTOFF);
        verify(resultRepository).saveAndFlush(result);
    }

    @Test
    void rejectsPendingWhenCutoffIsAfterTransactionOccurredAt() {
        FinancialTransaction transaction = stubStoredTransaction();
        when(resultRepository.findMaximumVersionByTransactionPk(1L))
                .thenReturn(0);

        assertThatThrownBy(() -> service.createPending(
                transaction.getTransactionId(),
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF.plusNanos(1),
                "trace_cutoff_future"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "evaluationCutoffAt must exactly match transaction "
                                + "occurredAt"
                );

        verify(resultRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsPendingWhenCutoffIsBeforeTransactionOccurredAt() {
        FinancialTransaction transaction = stubStoredTransaction();
        when(resultRepository.findMaximumVersionByTransactionPk(1L))
                .thenReturn(0);

        assertThatThrownBy(() -> service.createPending(
                transaction.getTransactionId(),
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF.minusNanos(1),
                "trace_cutoff_past"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "evaluationCutoffAt must exactly match transaction "
                                + "occurredAt"
                );

        verify(resultRepository, never()).saveAndFlush(any());
    }

    private DetectionResult startedResult() {
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
        DetectionResult result = DetectionResult.pending(
                transaction,
                1,
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_rule_evidence"
        );
        result.start(CUTOFF.plusSeconds(1));
        return result;
    }

    private FinancialTransaction stubStoredTransaction() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction transaction = org.mockito.Mockito.mock(
                FinancialTransaction.class
        );
        when(transaction.getId()).thenReturn(1L);
        when(transaction.getTransactionId()).thenReturn(transactionId);
        when(transaction.getOccurredAt()).thenReturn(CUTOFF);
        when(transactionRepository.findByTransactionIdForUpdate(transactionId))
                .thenReturn(Optional.of(transaction));
        return transaction;
    }

    private RuleVersion publishedAmountVersion() {
        FraudRule rule = FraudRule.create(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                "Rule v1 test rule",
                "Rule v1 evidence contract test rule"
        );
        RuleVersion version = RuleVersion.draft(
                rule,
                1,
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition(),
                CUTOFF.minusSeconds(120),
                null
        );
        version.publish(CUTOFF.minusSeconds(180));
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

    private ObjectNode amountSummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000");
    }
}
