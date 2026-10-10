package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.validation.TransactionQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdoptedDetectionResultQueryServiceTest {
    private static final UUID ID = UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001");
    private final TransactionQueryValidator validator = new TransactionQueryValidator();
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final DetectionResultRepository results = mock(DetectionResultRepository.class);
    private final DetectionEvidenceRepository evidence = mock(DetectionEvidenceRepository.class);
    private final AdoptedDetectionResultQueryService service = new AdoptedDetectionResultQueryService(
            validator, transactions, results, evidence);
    private FinancialTransaction transaction;

    @BeforeEach
    void setup() {
        transaction = mock(FinancialTransaction.class);
        when(transaction.getId()).thenReturn(71L);
        when(transactions.findByTransactionId(ID)).thenReturn(Optional.of(transaction));
    }

    @Test
    void distinguishesNoHistoryAndEachUnadoptedLatestStateWithoutLeakingRisk() {
        assertThat(service.find(ID.toString()).availability()).isEqualTo("NO_HISTORY");
        for (var entry : List.of(
                new Object[]{DetectionAnalysisStatus.PENDING, "PENDING"},
                new Object[]{DetectionAnalysisStatus.IN_PROGRESS, "IN_PROGRESS"},
                new Object[]{DetectionAnalysisStatus.FAILED, "FAILED"},
                new Object[]{DetectionAnalysisStatus.COMPLETED, "COMPLETED_NOT_ADOPTED"})) {
            DetectionResult latest = mock(DetectionResult.class);
            when(latest.getDetectionResultVersion()).thenReturn(2);
            when(latest.getAnalysisStatus()).thenReturn((DetectionAnalysisStatus) entry[0]);
            when(results.findFirstByFinancialTransaction_IdOrderByDetectionResultVersionDesc(71L))
                    .thenReturn(Optional.of(latest));
            var response = service.find(ID.toString());
            assertThat(response.availability()).isEqualTo(entry[1]);
            assertThat(response.latestDetectionResultVersion()).isEqualTo(2);
            assertThat(response.adoptedResult()).isNull();
        }
        verifyNoInteractions(evidence);
    }

    @Test
    void readsOnlyAdoptedCompletedVersionAndItsRuleRowsWhenLatestIsReanalysis() {
        DetectionResult adopted = mock(DetectionResult.class);
        DetectionResult latest = mock(DetectionResult.class);
        DetectionEvidence rule = mock(DetectionEvidence.class);
        when(transaction.getAdoptedDetectionResult()).thenReturn(adopted);
        when(transaction.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(adopted.belongsTo(transaction)).thenReturn(true);
        when(adopted.getId()).thenReturn(30L);
        when(adopted.getDetectionResultId()).thenReturn(UUID.randomUUID());
        when(adopted.getDetectionResultVersion()).thenReturn(1);
        when(adopted.getAnalysisStatus()).thenReturn(DetectionAnalysisStatus.COMPLETED);
        when(adopted.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(adopted.getRiskScore()).thenReturn(55);
        when(adopted.getMlContribution()).thenReturn(null);
        when(adopted.getAnalysisCompletedAt()).thenReturn(Instant.parse("2026-07-23T01:15:32Z"));
        when(adopted.getRuleSetVersion()).thenReturn("rule-v1");
        when(adopted.getScoringPolicyVersion()).thenReturn("scoring-policy-v1");
        when(latest.getDetectionResultVersion()).thenReturn(2);
        when(latest.getAnalysisStatus()).thenReturn(DetectionAnalysisStatus.IN_PROGRESS);
        when(results.findFirstByFinancialTransaction_IdOrderByDetectionResultVersionDesc(71L))
                .thenReturn(Optional.of(latest));
        when(rule.getRuleCode()).thenReturn("R001");
        when(rule.getRuleVersion()).thenReturn("1");
        when(rule.getReasonCode()).thenReturn("TRANSFER_ABSOLUTE_HIGH_AMOUNT");
        when(rule.getScoreContribution()).thenReturn(15);
        when(evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                30L, DetectionEvidenceType.RULE)).thenReturn(List.of(rule));

        var response = service.find(ID.toString());
        assertThat(response.availability()).isEqualTo("AVAILABLE");
        assertThat(response.latestAnalysisStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.adoptedResult().detectionResultVersion()).isEqualTo(1);
        assertThat(response.adoptedResult().riskScore()).isEqualTo(55);
        assertThat(response.adoptedResult().ruleEvidence()).hasSize(1);
        assertThat(response.adoptedResult().mlStatus()).isEqualTo("RULE_ONLY");
        assertThat(response.adoptedResult().mlEvidence()).isEmpty();
        verify(evidence).findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                30L, DetectionEvidenceType.RULE);
    }

    @Test
    void refusesAnAdoptedResultFromAnotherTransaction() {
        DetectionResult adopted = mock(DetectionResult.class);
        when(transaction.getAdoptedDetectionResult()).thenReturn(adopted);
        assertThatThrownBy(() -> service.find(ID.toString()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(evidence);
    }
}
