package com.aifds.backend.detection.service;

import com.aifds.backend.detection.dto.AdoptedDetectionResultItemResponse;
import com.aifds.backend.detection.dto.AdoptedDetectionResultResponse;
import com.aifds.backend.detection.dto.AdoptedRuleEvidenceResponse;
import com.aifds.backend.detection.dto.AdoptedMlEvidenceResponse;
import com.aifds.backend.detection.dto.Scn003EvidenceResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.ml.MlDetectionPolicy;
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
import java.time.Instant;

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
                    item,
                    latest != null && latest.getAnalysisStatus() == DetectionAnalysisStatus.FAILED
                            && latest.getFailureCode() != null
                            && latest.getFailureCode().startsWith("ML_")
                            ? latest.getFailureCode() : null
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
        var mlRows = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                adopted.getId(), DetectionEvidenceType.ML);
        List<AdoptedMlEvidenceResponse> ml = mlRows.stream()
                .map(row -> new AdoptedMlEvidenceResponse(row.getReasonCode(),
                        row.getScoreContribution(),
                        row.getObservationSummary().path("probabilityBasisPoints").asInt(-1)))
                .toList();
        boolean applied = adopted.getMlContribution() != null;
        if (applied) {
            if (ml.size() != 1 || adopted.getRuleRiskScore() == null
                    || adopted.getMlProbabilityBasisPoints() == null
                    || ml.get(0).scoreContribution() != adopted.getMlContribution()
                    || ml.get(0).probabilityBasisPoints() != adopted.getMlProbabilityBasisPoints()
                    || adopted.getRiskScore() != Math.min(100,
                        adopted.getRuleRiskScore() + adopted.getMlContribution())
                    || adopted.getRiskLevel() != MlDetectionPolicy.riskLevel(
                        adopted.getRiskScore())) {
                throw new IllegalStateException("Adopted ML result is inconsistent");
            }
        } else if (!ml.isEmpty()) {
            throw new IllegalStateException("Rule-only result has ML evidence");
        }
        Scn003EvidenceResponse scn003 = null;
        if ("scoring-policy-v3".equals(adopted.getScoringPolicyVersion())
                || MlDetectionPolicy.SCN003_POLICY_VERSION.equals(
                        adopted.getScoringPolicyVersion())) {
            var external = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                    adopted.getId(), DetectionEvidenceType.EXTERNAL_RISK);
            var history = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                    adopted.getId(), DetectionEvidenceType.BEHAVIOR_PATTERN);
            if (external.size() != 1 || history.size() != 1) {
                throw new IllegalStateException("SCN-003 Evidence is incomplete");
            }
            var source = external.get(0).getObservationSummary();
            var prior = history.get(0).getObservationSummary();
            boolean matched = source.path("recipientAccountMatched").asBoolean(false);
            long r005Count = rules.stream().filter(row ->
                    RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT.equals(
                            row.ruleCode())).count();
            if (r005Count != (matched ? 1 : 0)
                    || !"SCN003-contract-v1".equals(source.path("sourceVersion").asText())
                    || !"SCN003-contract-v1".equals(prior.path("sourceVersion").asText())
                    || !prior.path("priorApprovedRecipientTransferObserved").isBoolean()) {
                throw new IllegalStateException("SCN-003 Evidence contradicts R005");
            }
            scn003 = new Scn003EvidenceResponse("SCN003-contract-v1",
                    source.path("providerCode").asText(),
                    Instant.parse(source.path("providerAsOf").asText()),
                    Instant.parse(source.path("lookedUpAt").asText()),
                    matched, prior.path("priorApprovedRecipientTransferObserved").booleanValue());
        }
        return new AdoptedDetectionResultItemResponse(
                adopted.getDetectionResultId(), adopted.getDetectionResultVersion(),
                adopted.getRiskLevel().name(), adopted.getRiskScore(),
                adopted.getAnalysisCompletedAt(), adopted.getRuleSetVersion(),
                adopted.getScoringPolicyVersion(), rules,
                applied ? adopted.getRuleRiskScore() : adopted.getRiskScore(),
                adopted.getMlContribution(), applied ? "APPLIED" : "RULE_ONLY",
                applied ? adopted.getModelVersion() : null,
                adopted.getMlFeatureVersion(), adopted.getModelSha256(), ml, scn003
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
