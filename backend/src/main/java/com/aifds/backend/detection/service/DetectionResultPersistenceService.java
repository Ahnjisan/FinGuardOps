package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DetectionResultPersistenceService {

    private final FinancialTransactionRepository transactionRepository;
    private final DetectionResultRepository detectionResultRepository;
    private final DetectionEvidenceRepository evidenceRepository;
    private final RuleVersionRepository ruleVersionRepository;

    public DetectionResultPersistenceService(
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

    @Transactional
    public DetectionResult createPending(
            UUID transactionId,
            String ruleSetVersion,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            Instant evaluationCutoffAt,
            String analysisTraceId
    ) {
        FinancialTransaction transaction = transactionRepository
                .findByTransactionIdForUpdate(transactionId)
                .orElseThrow(TransactionNotFoundException::new);
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
        return detectionResultRepository.saveAndFlush(pending);
    }

    @Transactional
    public void start(UUID detectionResultId, Instant startedAt) {
        DetectionResult result = resultForUpdate(detectionResultId);
        result.start(startedAt);
        detectionResultRepository.saveAndFlush(result);
    }

    @Transactional
    public void complete(
            UUID detectionResultId,
            int riskScore,
            RiskLevel riskLevel,
            Instant completedAt,
            List<RuleEvidenceDraft> evidenceDrafts
    ) {
        DetectionResult result = resultForUpdate(detectionResultId);
        List<DetectionEvidence> evidence = evidenceDrafts.stream()
                .map(draft -> RuleEvidenceAssembler.assemble(
                        result,
                        findRuleVersion(draft.ruleVersionId()),
                        draft
                ))
                .toList();
        evidenceRepository.saveAllAndFlush(evidence);
        result.complete(riskScore, riskLevel, completedAt);
        detectionResultRepository.saveAndFlush(result);
    }

    @Transactional
    public void fail(
            UUID detectionResultId,
            String failureCode,
            Instant failedAt
    ) {
        DetectionResult result = resultForUpdate(detectionResultId);
        result.fail(failureCode, failedAt);
        detectionResultRepository.saveAndFlush(result);
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
}
