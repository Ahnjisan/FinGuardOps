package com.aifds.backend.detection.service;

import com.aifds.backend.detection.dto.AdoptedDetectionResultItemResponse;
import com.aifds.backend.detection.dto.AdoptedDetectionResultResponse;
import com.aifds.backend.detection.dto.AdoptedRuleEvidenceResponse;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.exception.TransactionQueryTimeoutException;
import com.aifds.backend.transaction.exception.TransactionQueryUnavailableException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.validation.TransactionQueryValidator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
// Transaction, latest analysis and RULE rows must come from one PostgreSQL snapshot.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdoptedDetectionResultQueryService {
    private final TransactionQueryValidator validator;
    private final FinancialTransactionRepository transactions;
    private final DetectionResultRepository results;
    private final DetectionEvidenceRepository evidence;

    public AdoptedDetectionResultQueryService(
            TransactionQueryValidator validator,
            FinancialTransactionRepository transactions,
            DetectionResultRepository results,
            DetectionEvidenceRepository evidence
    ) {
        this.validator = validator;
        this.transactions = transactions;
        this.results = results;
        this.evidence = evidence;
    }

    public AdoptedDetectionResultResponse find(String rawTransactionId) {
        UUID transactionId = validator.validateTransactionId(rawTransactionId);
        try {
            FinancialTransaction transaction = transactions.findByTransactionId(transactionId)
                    .orElseThrow(TransactionNotFoundException::new);
            DetectionResult latest = results
                    .findFirstByFinancialTransaction_IdOrderByDetectionResultVersionDesc(transaction.getId())
                    .orElse(null);
            DetectionResult adopted = transaction.getAdoptedDetectionResult();
            if (adopted != null && (latest == null
                    || adopted.getDetectionResultVersion() > latest.getDetectionResultVersion())) {
                throw new IllegalStateException("Adopted detection result version is inconsistent");
            }
            String availability = adopted == null
                    ? latest == null ? "NO_HISTORY" : switch (latest.getAnalysisStatus()) {
                        case PENDING -> "PENDING";
                        case IN_PROGRESS -> "IN_PROGRESS";
                        case FAILED -> "FAILED";
                        case COMPLETED -> "COMPLETED_NOT_ADOPTED";
                    }
                    : "AVAILABLE";
            AdoptedDetectionResultItemResponse item = adopted == null ? null : project(transaction, adopted);
            return new AdoptedDetectionResultResponse(
                    transactionId,
                    availability,
                    latest == null ? null : latest.getDetectionResultVersion(),
                    latest == null ? null : latest.getAnalysisStatus().name(),
                    item
            );
        } catch (DataAccessException exception) {
            if (hasCause(exception, QueryTimeoutException.class)) {
                throw new TransactionQueryTimeoutException(exception);
            }
            if (hasCause(exception, TransientDataAccessResourceException.class)
                    || hasCause(exception, DataAccessResourceFailureException.class)) {
                throw new TransactionQueryUnavailableException(exception);
            }
            throw exception;
        }
    }

    private AdoptedDetectionResultItemResponse project(
            FinancialTransaction transaction, DetectionResult adopted
    ) {
        if (!adopted.belongsTo(transaction)
                || adopted.getAnalysisStatus() != DetectionAnalysisStatus.COMPLETED
                || adopted.getRiskLevel() != transaction.getRiskLevel()
                || adopted.getRiskScore() == null
                || adopted.getAnalysisCompletedAt() == null) {
            throw new IllegalStateException("Adopted detection result is inconsistent");
        }
        List<AdoptedRuleEvidenceResponse> rules = evidence
                .findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                        adopted.getId(), DetectionEvidenceType.RULE)
                .stream()
                .map(row -> new AdoptedRuleEvidenceResponse(
                        row.getRuleCode(), row.getRuleVersion(),
                        row.getReasonCode(), row.getScoreContribution()))
                .toList();
        return new AdoptedDetectionResultItemResponse(
                adopted.getDetectionResultId(), adopted.getDetectionResultVersion(),
                adopted.getRiskLevel().name(), adopted.getRiskScore(),
                adopted.getAnalysisCompletedAt(), adopted.getRuleSetVersion(),
                adopted.getScoringPolicyVersion(), rules
        );
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        for (int i = 0; i < 32 && current != null; i++) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
