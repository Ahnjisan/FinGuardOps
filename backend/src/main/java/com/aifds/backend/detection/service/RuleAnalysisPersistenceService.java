package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RuleAnalysisPersistenceService {

    private final FinancialTransactionRepository transactionRepository;
    private final DetectionResultRepository detectionResultRepository;
    private final DetectionEvidenceRepository evidenceRepository;
    private final RuleVersionRepository ruleVersionRepository;

    public RuleAnalysisPersistenceService(
            FinancialTransactionRepository transactionRepository,
            DetectionResultRepository detectionResultRepository,
            DetectionEvidenceRepository evidenceRepository,
            RuleVersionRepository ruleVersionRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.detectionResultRepository = detectionResultRepository;
        this.evidenceRepository = evidenceRepository;
        this.ruleVersionRepository = ruleVersionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartedRuleAnalysis startAnalysis(
            UUID transactionId,
            String ruleSetVersion,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            Instant evaluationCutoffAt,
            String analysisTraceId,
            Instant startedAt
    ) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        FinancialTransaction transaction = transactionRepository
                .findByTransactionIdForUpdate(transactionId)
                .orElseThrow(TransactionNotFoundException::new);
        validateCanStart(transaction);
        if (!transaction.getOccurredAt().equals(evaluationCutoffAt)) {
            throw new IllegalArgumentException(
                    "evaluationCutoffAt must exactly match transaction "
                            + "occurredAt"
            );
        }

        int nextVersion = Math.addExact(
                detectionResultRepository.findMaximumVersionByTransactionPk(
                        transaction.getId()
                ),
                1
        );
        DetectionResult pending = DetectionResult.pending(
                transaction,
                nextVersion,
                ruleSetVersion,
                scoringPolicyVersion,
                featureVersion,
                modelVersion,
                evaluationCutoffAt,
                analysisTraceId
        );
        DetectionResult saved = detectionResultRepository.saveAndFlush(
                pending
        );

        saved.start(startedAt);
        transaction.startAnalysis();
        transactionRepository.saveAndFlush(transaction);

        return new StartedRuleAnalysis(
                transaction.getTransactionId(),
                saved.getDetectionResultId(),
                saved.getDetectionResultVersion(),
                saved.getRuleSetVersion(),
                saved.getScoringPolicyVersion(),
                saved.getFeatureVersion(),
                saved.getModelVersion(),
                saved.getEvaluationCutoffAt(),
                saved.getAnalysisTraceId()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAndAdopt(
            StartedRuleAnalysis started,
            int riskScore,
            RiskLevel riskLevel,
            Instant completedAt,
            List<RuleEvidenceDraft> evidenceDrafts
    ) {
        StartedRuleAnalysis validatedStarted = Objects.requireNonNull(
                started,
                "started must not be null"
        );
        FinancialTransaction transaction = transactionForUpdate(
                validatedStarted.transactionId()
        );
        DetectionResult result = resultForUpdate(
                validatedStarted.detectionResultId()
        );

        requireTransactionStatus(
                transaction,
                TransactionProcessingStatus.ANALYZING
        );
        requireResultStatus(result, DetectionAnalysisStatus.IN_PROGRESS);
        if (!result.belongsTo(transaction)) {
            throw new IllegalArgumentException(
                    "Detection result must belong to the locked transaction"
            );
        }
        requireStartedMatches(validatedStarted, transaction, result);

        List<RuleEvidenceDraft> drafts = List.copyOf(
                Objects.requireNonNull(
                        evidenceDrafts,
                        "evidenceDrafts must not be null"
                )
        );
        List<DetectionEvidence> evidence = drafts.stream()
                .map(draft -> RuleEvidenceAssembler.assemble(
                        result,
                        findRuleVersion(draft.ruleVersionId()),
                        draft
                ))
                .toList();
        evidenceRepository.saveAllAndFlush(evidence);

        result.complete(riskScore, riskLevel, completedAt);
        detectionResultRepository.saveAndFlush(result);

        transaction.adoptDetectionResult(result);
        transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failAnalysis(
            StartedRuleAnalysis started,
            String failureCode,
            Instant failedAt
    ) {
        StartedRuleAnalysis validatedStarted = Objects.requireNonNull(
                started,
                "started must not be null"
        );
        FinancialTransaction transaction = transactionForUpdate(
                validatedStarted.transactionId()
        );
        DetectionResult result = resultForUpdate(
                validatedStarted.detectionResultId()
        );

        if (!result.belongsTo(transaction)) {
            throw new IllegalArgumentException(
                    "Detection result must belong to the locked transaction"
            );
        }
        requireStartedMatches(validatedStarted, transaction, result);
        requireTransactionStatus(
                transaction,
                TransactionProcessingStatus.ANALYZING
        );
        requireResultStatus(result, DetectionAnalysisStatus.IN_PROGRESS);

        result.fail(failureCode, failedAt);
        transaction.failAnalysis();
        transactionRepository.saveAndFlush(transaction);
    }

    private FinancialTransaction transactionForUpdate(UUID transactionId) {
        return transactionRepository.findByTransactionIdForUpdate(
                transactionId
        ).orElseThrow(TransactionNotFoundException::new);
    }

    private DetectionResult resultForUpdate(UUID detectionResultId) {
        return detectionResultRepository.findByDetectionResultIdForUpdate(
                detectionResultId
        ).orElseThrow(
                () -> new IllegalArgumentException(
                        "Detection result does not exist"
                )
        );
    }

    private RuleVersion findRuleVersion(UUID ruleVersionId) {
        return ruleVersionRepository.findByRuleVersionId(ruleVersionId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Rule version does not exist"
                        )
                );
    }

    private void validateCanStart(FinancialTransaction transaction) {
        requireTransactionStatus(
                transaction,
                TransactionProcessingStatus.RECEIVED
        );
        if (transaction.getAdoptedDetectionResult() != null
                || transaction.getRiskLevel() != null
                || transaction.getRiskResponseOutcome() != null) {
            throw new IllegalStateException(
                    "Transaction must not have an analysis outcome"
            );
        }
    }

    private void requireTransactionStatus(
            FinancialTransaction transaction,
            TransactionProcessingStatus expected
    ) {
        if (transaction.getProcessingStatus() != expected) {
            throw new IllegalStateException(
                    "Transaction processing status must be " + expected
            );
        }
    }

    private void requireResultStatus(
            DetectionResult result,
            DetectionAnalysisStatus expected
    ) {
        if (result.getAnalysisStatus() != expected) {
            throw new IllegalStateException(
                    "Detection result status must be " + expected
            );
        }
    }

    private void requireStartedMatches(
            StartedRuleAnalysis started,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        boolean matches = started.transactionId().equals(
                transaction.getTransactionId()
        )
                && started.detectionResultId().equals(
                result.getDetectionResultId()
        )
                && started.detectionResultVersion()
                == result.getDetectionResultVersion()
                && started.ruleSetVersion().equals(result.getRuleSetVersion())
                && started.scoringPolicyVersion().equals(
                result.getScoringPolicyVersion()
        )
                && started.featureVersion().equals(result.getFeatureVersion())
                && Objects.equals(
                started.modelVersion(),
                result.getModelVersion()
        )
                && started.evaluationCutoffAt().equals(
                result.getEvaluationCutoffAt()
        )
                && started.analysisTraceId().equals(
                result.getAnalysisTraceId()
        );
        if (!matches) {
            throw new IllegalArgumentException(
                    "Started analysis does not match the stored result"
            );
        }
    }
}
