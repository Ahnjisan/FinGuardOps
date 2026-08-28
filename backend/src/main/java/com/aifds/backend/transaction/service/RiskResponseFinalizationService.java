package com.aifds.backend.transaction.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.service.FraudCaseLinkResult;
import com.aifds.backend.fraudcase.service.FraudCasePersistenceService;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.policy.RiskResponseDecision;
import com.aifds.backend.transaction.policy.RiskResponseDecisionPolicy;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RiskResponseFinalizationService {

    private final FinancialTransactionRepository transactionRepository;
    private final FraudCasePersistenceService fraudCasePersistenceService;
    private final AuditLogPersistenceService auditLogPersistenceService;
    private final RiskResponseDecisionPolicy decisionPolicy;
    private final TransactionProcessingMetricsRecorder metricsRecorder;

    @Autowired
    public RiskResponseFinalizationService(
            FinancialTransactionRepository transactionRepository,
            FraudCasePersistenceService fraudCasePersistenceService,
            AuditLogPersistenceService auditLogPersistenceService,
            TransactionProcessingMetricsRecorder metricsRecorder
    ) {
        this.transactionRepository = transactionRepository;
        this.fraudCasePersistenceService = fraudCasePersistenceService;
        this.auditLogPersistenceService = auditLogPersistenceService;
        this.decisionPolicy = new RiskResponseDecisionPolicy();
        this.metricsRecorder = metricsRecorder == null
                ? TransactionProcessingMetricsRecorder.noop()
                : metricsRecorder;
    }

    public RiskResponseFinalizationService(
            FinancialTransactionRepository transactionRepository,
            FraudCasePersistenceService fraudCasePersistenceService,
            AuditLogPersistenceService auditLogPersistenceService
    ) {
        this(
                transactionRepository,
                fraudCasePersistenceService,
                auditLogPersistenceService,
                TransactionProcessingMetricsRecorder.noop()
        );
    }

    @Transactional
    public RiskResponseFinalizationResult finalizeRiskResponse(
            UUID transactionId
    ) {
        UUID requestedTransactionId = Objects.requireNonNull(
                transactionId,
                "transactionId must not be null"
        );
        FinancialTransaction transaction = transactionRepository
                .findByTransactionIdForUpdate(requestedTransactionId)
                .orElseThrow(TransactionNotFoundException::new);
        DetectionResult adoptedResult = validateEligible(transaction);
        RiskResponseDecision decision = decisionPolicy.decide(
                transaction.getRiskLevel()
        );

        FraudCaseLinkResult caseResult = null;
        if (decision.caseRequired()) {
            caseResult = requireCaseResult(
                    fraudCasePersistenceService
                            .createOrReuseForHighRiskTransaction(
                                    requestedTransactionId
                            ),
                    requestedTransactionId
            );
        }

        transaction.finalizeRiskResponse(decision);
        transactionRepository.saveAndFlush(transaction);

        appendAuditLogs(
                transaction,
                adoptedResult,
                decision,
                caseResult
        );

        recordTerminalAfterCommit(transaction);

        return new RiskResponseFinalizationResult(
                transaction.getTransactionId(),
                adoptedResult.getDetectionResultId(),
                transaction.getRiskLevel(),
                transaction.getProcessingStatus(),
                transaction.getRiskResponseOutcome(),
                caseResult == null ? null : caseResult.caseId(),
                caseResult != null && caseResult.newlyCreated()
        );
    }

    private void recordTerminalAfterCommit(
            FinancialTransaction transaction
    ) {
        Instant createdAt = transaction.getCreatedAt();
        Instant terminalAt = transaction.getUpdatedAt();
        Runnable recording = () -> metricsRecorder.recordTransactionTerminal(
                transaction.getProcessingStatus(),
                transaction.getRiskLevel(),
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                duration(createdAt, terminalAt)
        );
        afterCommit(recording);
    }

    private Duration duration(Instant createdAt, Instant terminalAt) {
        if (createdAt == null || terminalAt == null) {
            return null;
        }
        Duration duration = Duration.between(createdAt, terminalAt);
        return duration.isNegative() ? null : duration;
    }

    private void afterCommit(Runnable operation) {
        try {
            if (!TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                operation.run();
                            } catch (Throwable ignored) {
                                // Observability cannot change committed work.
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            // Synchronization registration cannot change business work.
        }
    }

    private DetectionResult validateEligible(
            FinancialTransaction transaction
    ) {
        if (transaction.getProcessingStatus()
                != TransactionProcessingStatus.ANALYZED) {
            throw new IllegalStateException(
                    "Transaction processing status must be ANALYZED"
            );
        }
        DetectionResult adoptedResult = transaction
                .getAdoptedDetectionResult();
        if (adoptedResult == null) {
            throw new IllegalStateException(
                    "Transaction must have an adopted detection result"
            );
        }
        if (!adoptedResult.belongsTo(transaction)) {
            throw new IllegalStateException(
                    "Adopted detection result must belong to the transaction"
            );
        }
        if (adoptedResult.getAnalysisStatus()
                != DetectionAnalysisStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Adopted detection result must be COMPLETED"
            );
        }
        if (transaction.getRiskLevel() == null) {
            throw new IllegalStateException(
                    "Transaction risk level must not be null"
            );
        }
        if (adoptedResult.getRiskLevel() != transaction.getRiskLevel()) {
            throw new IllegalStateException(
                    "Transaction risk level must match the adopted result"
            );
        }
        if (transaction.getRiskResponseOutcome() != null) {
            throw new IllegalStateException(
                    "Risk response outcome must not already be finalized"
            );
        }
        return adoptedResult;
    }

    private FraudCaseLinkResult requireCaseResult(
            FraudCaseLinkResult caseResult,
            UUID transactionId
    ) {
        FraudCaseLinkResult validated = Objects.requireNonNull(
                caseResult,
                "A required case result must not be null"
        );
        if (!transactionId.equals(validated.transactionId())) {
            throw new IllegalStateException(
                    "Case result must belong to the finalized transaction"
            );
        }
        if (!validated.caseStatus().isActive()) {
            throw new IllegalStateException(
                    "Finalized transaction must be linked to an active case"
            );
        }
        if (validated.newlyCreated()
                && validated.caseStatus() != FraudCaseStatus.OPEN) {
            throw new IllegalStateException(
                    "A newly created case must have OPEN status"
            );
        }
        return validated;
    }

    private void appendAuditLogs(
            FinancialTransaction transaction,
            DetectionResult adoptedResult,
            RiskResponseDecision decision,
            FraudCaseLinkResult caseResult
    ) {
        if (caseResult != null && caseResult.newlyCreated()) {
            auditLogPersistenceService.append(caseCreatedDraft(
                    transaction,
                    adoptedResult,
                    caseResult
            ));
            auditLogPersistenceService.append(caseLinkedDraft(
                    transaction,
                    adoptedResult,
                    caseResult
            ));
        }
        auditLogPersistenceService.append(riskResponseDraft(
                transaction,
                adoptedResult,
                decision
        ));
        auditLogPersistenceService.append(statusChangedDraft(
                transaction,
                adoptedResult,
                decision
        ));
    }

    private AuditLogDraft caseCreatedDraft(
            FinancialTransaction transaction,
            DetectionResult result,
            FraudCaseLinkResult caseResult
    ) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseResult.caseId(),
                transaction.getTransactionId(),
                caseResult.caseId(),
                result.getAnalysisTraceId(),
                null,
                object("caseStatus", caseResult.caseStatus().name()),
                detectionMetadata(result)
        );
    }

    private AuditLogDraft caseLinkedDraft(
            FinancialTransaction transaction,
            DetectionResult result,
            FraudCaseLinkResult caseResult
    ) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.CASE_TRANSACTION_LINKED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseResult.caseId(),
                transaction.getTransactionId(),
                caseResult.caseId(),
                result.getAnalysisTraceId(),
                null,
                object("linked", true),
                detectionMetadata(result)
        );
    }

    private AuditLogDraft riskResponseDraft(
            FinancialTransaction transaction,
            DetectionResult result,
            RiskResponseDecision decision
    ) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transaction.getTransactionId(),
                transaction.getTransactionId(),
                null,
                result.getAnalysisTraceId(),
                null,
                object(
                        "riskResponseOutcome",
                        decision.riskResponseOutcome().name()
                ),
                transactionMetadata(result, decision)
        );
    }

    private AuditLogDraft statusChangedDraft(
            FinancialTransaction transaction,
            DetectionResult result,
            RiskResponseDecision decision
    ) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transaction.getTransactionId(),
                transaction.getTransactionId(),
                null,
                result.getAnalysisTraceId(),
                object(
                        "processingStatus",
                        TransactionProcessingStatus.ANALYZED.name()
                ),
                object(
                        "processingStatus",
                        decision.targetTransactionStatus().name()
                ),
                transactionMetadata(result, decision)
        );
    }

    private ObjectNode detectionMetadata(DetectionResult result) {
        return JsonNodeFactory.instance.objectNode()
                .put(
                        "detectionResultId",
                        result.getDetectionResultId().toString()
                )
                .put(
                        "detectionResultVersion",
                        result.getDetectionResultVersion()
                );
    }

    private ObjectNode transactionMetadata(
            DetectionResult result,
            RiskResponseDecision decision
    ) {
        return detectionMetadata(result)
                .put("sourceRiskLevel", decision.sourceRiskLevel().name());
    }

    private ObjectNode object(String field, String value) {
        return JsonNodeFactory.instance.objectNode().put(field, value);
    }

    private ObjectNode object(String field, boolean value) {
        return JsonNodeFactory.instance.objectNode().put(field, value);
    }
}
