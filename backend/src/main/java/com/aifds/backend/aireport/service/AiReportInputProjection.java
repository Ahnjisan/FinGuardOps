package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AiReportInputProjection {
    private final CaseTransactionRepository links;
    private final FinancialTransactionRepository transactions;
    private final DetectionEvidenceRepository evidence;

    public AiReportInputProjection(CaseTransactionRepository links,
                                   FinancialTransactionRepository transactions,
                                   DetectionEvidenceRepository evidence) {
        this.links = links;
        this.transactions = transactions;
        this.evidence = evidence;
    }

    public Projection project(FraudCase fraudCase, int version, String traceId) {
        var linked = links.findTransactionIdsByFraudCasePk(fraudCase.getId(), PageRequest.of(0, 2));
        if (linked.getTotalElements() != 1 || linked.getContent().size() != 1) {
            throw new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        UUID transactionId = linked.getContent().get(0);
        FinancialTransaction transaction = transactions.findByTransactionId(transactionId)
                .orElseThrow(() -> new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR"));
        DetectionResult result = transaction.getAdoptedDetectionResult();
        if (result == null || !result.belongsTo(transaction)
                || result.getAnalysisStatus() != DetectionAnalysisStatus.COMPLETED
                || result.getDetectionResultVersion() != version
                || result.getRiskLevel() != transaction.getRiskLevel()) {
            throw new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        if (result.getRiskLevel() != RiskLevel.HIGH && result.getRiskLevel() != RiskLevel.CRITICAL) {
            throw new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        var rules = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                result.getId(), DetectionEvidenceType.RULE).stream()
                .map(item -> new AiReportDtos.RuleEvidence(item.getRuleCode(), item.getRuleVersion(),
                        item.getReasonCode(), item.getScoreContribution()))
                .toList();
        if (rules.isEmpty() || rules.size() > 20) {
            throw new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        return new Projection(result.getId(), new AiReportDtos.GenerationRequest(
                fraudCase.getCaseId(), version, result.getRiskLevel().name(), result.getRiskScore(),
                result.getRuleSetVersion(), List.copyOf(rules), traceId));
    }

    public record Projection(long detectionPk, AiReportDtos.GenerationRequest request) { }
}
