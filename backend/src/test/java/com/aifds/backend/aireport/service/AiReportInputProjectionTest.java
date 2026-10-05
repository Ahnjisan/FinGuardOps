package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiReportInputProjectionTest {
    private final CaseTransactionRepository links = mock(CaseTransactionRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final DetectionEvidenceRepository evidence = mock(DetectionEvidenceRepository.class);
    private final AiReportInputProjection projection = new AiReportInputProjection(links, transactions, evidence);
    private final FraudCase fraudCase = mock(FraudCase.class);

    AiReportInputProjectionTest() {
        when(fraudCase.getId()).thenReturn(10L);
    }

    @Test
    void zeroOrMultipleTransactionsAreRefusedBeforeAnyRepresentativeSelection() {
        when(links.findTransactionIdsByFraudCasePk(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertEquals("VALIDATION_ERROR", assertThrows(AiReportException.class,
                () -> projection.project(fraudCase, 1, "trace-test-001")).code());
        when(links.findTransactionIdsByFraudCasePk(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(UUID.randomUUID(), UUID.randomUUID())));
        assertEquals("VALIDATION_ERROR", assertThrows(AiReportException.class,
                () -> projection.project(fraudCase, 1, "trace-test-001")).code());
    }

    @Test
    void foreignUncompletedLowRiskAndStaleAdoptedResultsAreRefused() {
        UUID transactionId = UUID.randomUUID();
        var transaction = mock(FinancialTransaction.class);
        var result = mock(DetectionResult.class);
        when(links.findTransactionIdsByFraudCasePk(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transactionId)));
        when(transactions.findByTransactionId(transactionId)).thenReturn(Optional.of(transaction));
        when(transaction.getAdoptedDetectionResult()).thenReturn(result);
        when(result.belongsTo(transaction)).thenReturn(false);
        assertThrows(AiReportException.class, () -> projection.project(fraudCase, 1, "trace-test-001"));
        when(result.belongsTo(transaction)).thenReturn(true);
        when(result.getAnalysisStatus()).thenReturn(DetectionAnalysisStatus.PENDING);
        assertThrows(AiReportException.class, () -> projection.project(fraudCase, 1, "trace-test-001"));
        when(result.getAnalysisStatus()).thenReturn(DetectionAnalysisStatus.COMPLETED);
        when(result.getDetectionResultVersion()).thenReturn(2);
        assertThrows(AiReportException.class, () -> projection.project(fraudCase, 1, "trace-test-001"));
        when(result.getDetectionResultVersion()).thenReturn(1);
        when(result.getRiskLevel()).thenReturn(RiskLevel.LOW);
        assertThrows(AiReportException.class, () -> projection.project(fraudCase, 1, "trace-test-001"));
    }

    @Test
    void completedHighRiskAdoptedRuleProjectsOnlyApprovedFields() {
        UUID transactionId = UUID.randomUUID();
        var transaction = mock(FinancialTransaction.class);
        var result = mock(DetectionResult.class);
        var rule = mock(DetectionEvidence.class);
        when(links.findTransactionIdsByFraudCasePk(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transactionId)));
        when(transactions.findByTransactionId(transactionId)).thenReturn(Optional.of(transaction));
        when(transaction.getAdoptedDetectionResult()).thenReturn(result);
        when(transaction.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(result.belongsTo(transaction)).thenReturn(true);
        when(result.getAnalysisStatus()).thenReturn(DetectionAnalysisStatus.COMPLETED);
        when(result.getDetectionResultVersion()).thenReturn(3);
        when(result.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(result.getRiskScore()).thenReturn(81);
        when(result.getRuleSetVersion()).thenReturn("rules-1");
        when(result.getId()).thenReturn(77L);
        when(rule.getRuleCode()).thenReturn("R001");
        when(rule.getRuleVersion()).thenReturn("1");
        when(rule.getReasonCode()).thenReturn("NEW_DEVICE");
        when(rule.getScoreContribution()).thenReturn(15);
        when(evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                77L, DetectionEvidenceType.RULE)).thenReturn(List.of(rule));
        var projected = projection.project(fraudCase, 3, "trace-test-001");
        assertEquals(77L, projected.detectionPk());
        assertEquals(3, projected.request().detectionResultVersion());
        assertEquals("NEW_DEVICE", projected.request().ruleEvidence().get(0).reasonCode());
        when(transaction.getRiskLevel()).thenReturn(RiskLevel.CRITICAL);
        assertThrows(AiReportException.class, () -> projection.project(fraudCase, 3, "trace-test-001"));
    }
}
