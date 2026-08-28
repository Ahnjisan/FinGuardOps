package com.aifds.backend.idempotency.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.repository.AuditLogRepository;
import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditLog;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditResult;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.repository.IdempotencyRecoveryAuditLogRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.TransactionFinalResponseSnapshot;
import com.aifds.backend.transaction.service.TransactionIntakeSnapshotCodec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IdempotencyRecoveryTransaction {

    private static final int SUCCESS_HTTP_STATUS = 201;
    private static final Set<String> OUTCOME_FIELD = Set.of(
            "riskResponseOutcome"
    );
    private static final Set<String> STATUS_FIELD = Set.of(
            "processingStatus"
    );
    private static final Set<String> FINALIZATION_METADATA_FIELDS = Set.of(
            "sourceRiskLevel",
            "detectionResultId",
            "detectionResultVersion"
    );

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final DetectionEvidenceRepository evidenceRepository;
    private final CaseTransactionRepository caseTransactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final IdempotencyRecoveryAuditLogRepository recoveryAuditRepository;
    private final DatabaseTransactionTimestampProvider timestampProvider;
    private final TransactionIntakeSnapshotCodec snapshotCodec;
    private final IdempotencyService idempotencyService;

    public IdempotencyRecoveryTransaction(
            IdempotencyRecordRepository idempotencyRecordRepository,
            FinancialTransactionRepository transactionRepository,
            DetectionEvidenceRepository evidenceRepository,
            CaseTransactionRepository caseTransactionRepository,
            AuditLogRepository auditLogRepository,
            IdempotencyRecoveryAuditLogRepository recoveryAuditRepository,
            DatabaseTransactionTimestampProvider timestampProvider,
            TransactionIntakeSnapshotCodec snapshotCodec,
            IdempotencyService idempotencyService
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transactionRepository = transactionRepository;
        this.evidenceRepository = evidenceRepository;
        this.caseTransactionRepository = caseTransactionRepository;
        this.auditLogRepository = auditLogRepository;
        this.recoveryAuditRepository = recoveryAuditRepository;
        this.timestampProvider = timestampProvider;
        this.snapshotCodec = snapshotCodec;
        this.idempotencyService = idempotencyService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecoveryResult recover(
            long idempotencyRecordId,
            AuditActorType actorType,
            String actorId
    ) {
        Instant attemptedAt = timestampProvider.currentTransactionTimestamp();
        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(idempotencyRecordId)
                .orElse(null);
        if (record == null) {
            return reject(
                    idempotencyRecordId,
                    null,
                    actorType,
                    actorId,
                    IdempotencyRecoveryDecision.MISSING_IDEMPOTENCY_RECORD,
                    attemptedAt
            );
        }

        UUID transactionId = linkedTransactionId(record);
        IdempotencyRecoveryDecision idempotencyDecision =
                classifyIdempotency(record);
        if (idempotencyDecision != null) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    idempotencyDecision,
                    attemptedAt
            );
        }
        if (transactionId == null) {
            return reject(
                    idempotencyRecordId,
                    null,
                    actorType,
                    actorId,
                    IdempotencyRecoveryDecision.MISSING_TRANSACTION,
                    attemptedAt
            );
        }

        FinancialTransaction transaction = transactionRepository
                .findByTransactionIdForUpdate(transactionId)
                .orElse(null);
        if (transaction == null) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    IdempotencyRecoveryDecision.MISSING_TRANSACTION,
                    attemptedAt
            );
        }

        IdempotencyRecoveryDecision transactionDecision =
                classifyTransaction(transaction);
        if (transactionDecision != null) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    transactionDecision,
                    attemptedAt
            );
        }

        DetectionResult adoptedResult = transaction
                .getAdoptedDetectionResult();
        IdempotencyRecoveryDecision detectionDecision =
                classifyDetection(transaction, adoptedResult);
        if (detectionDecision != null) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    detectionDecision,
                    attemptedAt
            );
        }

        List<DetectionEvidence> evidence = evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        adoptedResult.getDetectionResultId()
                );
        if (!evidenceBelongsTo(evidence, adoptedResult)) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    IdempotencyRecoveryDecision.INCONSISTENT_FINAL_STATE,
                    attemptedAt
            );
        }

        List<CaseTransaction> caseLinks = caseTransactionRepository
                .findAllByTransactionPk(transaction.getId());
        UUID caseId = validatedCaseId(transaction, caseLinks);
        if ((caseId == null && requiresCase(transaction.getRiskLevel()))
                || (caseId != null
                && !requiresCase(transaction.getRiskLevel()))
                || !caseLinksValid(transaction, caseLinks)) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    IdempotencyRecoveryDecision
                            .INCONSISTENT_CASE_RELATIONSHIP,
                    attemptedAt
            );
        }

        List<AuditLog> finalizationLogs = auditLogRepository
                .findTransactionFinalizationLogs(transactionId);
        if (!validFinalizationLogs(
                finalizationLogs,
                transaction,
                adoptedResult
        )) {
            return reject(
                    idempotencyRecordId,
                    transactionId,
                    actorType,
                    actorId,
                    IdempotencyRecoveryDecision.FINALIZATION_AUDIT_MISMATCH,
                    attemptedAt
            );
        }

        TransactionFinalResponseSnapshot snapshot =
                new TransactionFinalResponseSnapshot(
                        transaction.getTransactionId(),
                        transaction.getProcessingStatus(),
                        transaction.getRiskLevel(),
                        transaction.getRiskResponseOutcome(),
                        adoptedResult.getDetectionResultId(),
                        caseId,
                        transaction.getCreatedAt()
                );
        JsonNode encoded = snapshotCodec.encodeV2(
                snapshot,
                SUCCESS_HTTP_STATUS,
                attemptedAt
        );
        idempotencyService.complete(
                idempotencyRecordId,
                transactionId,
                encoded,
                attemptedAt
        );
        appendAudit(
                idempotencyRecordId,
                transactionId,
                actorType,
                actorId,
                IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP,
                IdempotencyRecoveryAuditResult.RECOVERED,
                attemptedAt
        );
        return IdempotencyRecoveryResult.recovered(
                idempotencyRecordId,
                transactionId
        );
    }

    private UUID linkedTransactionId(IdempotencyRecord record) {
        FinancialTransaction linked = record.getFinancialTransaction();
        return linked == null ? null : linked.getTransactionId();
    }

    private IdempotencyRecoveryDecision classifyIdempotency(
            IdempotencyRecord record
    ) {
        if (record.getProcessingStatus()
                != IdempotencyProcessingStatus.IN_PROGRESS) {
            return IdempotencyRecoveryDecision.ALREADY_TERMINAL;
        }
        if (!IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE.equals(
                record.getOperationScope()
        ) || record.getResponseSnapshot() != null
                || record.getFailureCode() != null
                || record.getFinishedAt() != null) {
            return IdempotencyRecoveryDecision
                    .CONFLICTING_IDEMPOTENCY_DATA;
        }
        return null;
    }

    private IdempotencyRecoveryDecision classifyTransaction(
            FinancialTransaction transaction
    ) {
        return switch (transaction.getProcessingStatus()) {
            case RECEIVED, ANALYZING ->
                    IdempotencyRecoveryDecision.PROCESSING_INDETERMINATE;
            case ANALYZED ->
                    IdempotencyRecoveryDecision.FINALIZATION_INCOMPLETE;
            case FAILED ->
                    IdempotencyRecoveryDecision.CONFIRMED_DOMAIN_FAILURE;
            case APPROVED, ADDITIONAL_AUTH_REQUIRED, HELD -> null;
        };
    }

    private IdempotencyRecoveryDecision classifyDetection(
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        if (result == null) {
            return IdempotencyRecoveryDecision.INCONSISTENT_FINAL_STATE;
        }
        if (result.getAnalysisStatus() == DetectionAnalysisStatus.FAILED) {
            return IdempotencyRecoveryDecision.CONFIRMED_DOMAIN_FAILURE;
        }
        if (result.getAnalysisStatus() == DetectionAnalysisStatus.PENDING
                || result.getAnalysisStatus()
                == DetectionAnalysisStatus.IN_PROGRESS) {
            return IdempotencyRecoveryDecision.PROCESSING_INDETERMINATE;
        }
        if (!result.belongsTo(transaction)
                || result.getRiskLevel() != transaction.getRiskLevel()
                || !validFinalCombination(transaction)) {
            return IdempotencyRecoveryDecision.INCONSISTENT_FINAL_STATE;
        }
        return null;
    }

    private boolean validFinalCombination(FinancialTransaction transaction) {
        RiskLevel riskLevel = transaction.getRiskLevel();
        TransactionProcessingStatus status = transaction.getProcessingStatus();
        RiskResponseOutcome outcome = transaction.getRiskResponseOutcome();
        if (riskLevel == null || outcome == null) {
            return false;
        }
        return switch (riskLevel) {
            case LOW -> status == TransactionProcessingStatus.APPROVED
                    && outcome == RiskResponseOutcome.APPROVED;
            case MEDIUM -> status == TransactionProcessingStatus.APPROVED
                    && outcome
                    == RiskResponseOutcome.APPROVED_WITH_MONITORING;
            case HIGH -> status
                    == TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                    && outcome
                    == RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED;
            case CRITICAL -> status == TransactionProcessingStatus.HELD
                    && outcome == RiskResponseOutcome.HELD;
        };
    }

    private boolean evidenceBelongsTo(
            List<DetectionEvidence> evidence,
            DetectionResult adoptedResult
    ) {
        UUID expectedId = adoptedResult.getDetectionResultId();
        return evidence.stream().allMatch(item ->
                item.getDetectionResult() != null
                        && expectedId.equals(item.getDetectionResult()
                        .getDetectionResultId())
        );
    }

    private UUID validatedCaseId(
            FinancialTransaction transaction,
            List<CaseTransaction> links
    ) {
        boolean required = requiresCase(transaction.getRiskLevel());
        if ((!required && !links.isEmpty())
                || (required && links.size() != 1)) {
            return null;
        }
        return required ? links.get(0).getFraudCase().getCaseId() : null;
    }

    private boolean caseLinksValid(
            FinancialTransaction transaction,
            List<CaseTransaction> links
    ) {
        if (!requiresCase(transaction.getRiskLevel())) {
            return links.isEmpty();
        }
        return links.size() == 1
                && links.get(0).belongsTo(
                links.get(0).getFraudCase(),
                transaction
        );
    }

    private boolean requiresCase(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private boolean validFinalizationLogs(
            List<AuditLog> logs,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        if (logs.size() != 2) {
            return false;
        }
        List<AuditLog> riskLogs = logs.stream()
                .filter(log -> log.getAction()
                        == AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED)
                .toList();
        List<AuditLog> statusLogs = logs.stream()
                .filter(log -> log.getAction()
                        == AuditAction.TRANSACTION_STATUS_CHANGED)
                .toList();
        return riskLogs.size() == 1
                && statusLogs.size() == 1
                && validRiskAudit(riskLogs.get(0), transaction, result)
                && validStatusAudit(statusLogs.get(0), transaction, result);
    }

    private boolean validRiskAudit(
            AuditLog audit,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        return commonAuditMatches(audit, transaction)
                && audit.getReasonCode()
                == AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY
                && audit.getBeforeValueSummary() == null
                && exactText(
                audit.getAfterValueSummary(),
                OUTCOME_FIELD,
                "riskResponseOutcome",
                transaction.getRiskResponseOutcome().name()
        ) && metadataMatches(audit.getMetadata(), transaction, result);
    }

    private boolean validStatusAudit(
            AuditLog audit,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        return commonAuditMatches(audit, transaction)
                && audit.getReasonCode()
                == AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY
                && exactText(
                audit.getBeforeValueSummary(),
                STATUS_FIELD,
                "processingStatus",
                TransactionProcessingStatus.ANALYZED.name()
        ) && exactText(
                audit.getAfterValueSummary(),
                STATUS_FIELD,
                "processingStatus",
                transaction.getProcessingStatus().name()
        ) && metadataMatches(audit.getMetadata(), transaction, result);
    }

    private boolean commonAuditMatches(
            AuditLog audit,
            FinancialTransaction transaction
    ) {
        UUID transactionId = transaction.getTransactionId();
        return audit.getTargetType() == AuditTargetType.FINANCIAL_TRANSACTION
                && transactionId.equals(audit.getTargetId())
                && transactionId.equals(audit.getTransactionId())
                && audit.getCaseId() == null;
    }

    private boolean metadataMatches(
            JsonNode metadata,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        return fields(metadata).equals(FINALIZATION_METADATA_FIELDS)
                && textEquals(
                metadata,
                "sourceRiskLevel",
                transaction.getRiskLevel().name()
        ) && textEquals(
                metadata,
                "detectionResultId",
                result.getDetectionResultId().toString()
        ) && metadata.get("detectionResultVersion").isIntegralNumber()
                && metadata.get("detectionResultVersion").canConvertToInt()
                && metadata.get("detectionResultVersion").intValue()
                == result.getDetectionResultVersion();
    }

    private boolean exactText(
            JsonNode value,
            Set<String> expectedFields,
            String field,
            String expected
    ) {
        return fields(value).equals(expectedFields)
                && textEquals(value, field, expected);
    }

    private boolean textEquals(
            JsonNode value,
            String field,
            String expected
    ) {
        JsonNode child = value == null ? null : value.get(field);
        return child != null
                && child.isTextual()
                && expected.equals(child.textValue());
    }

    private Set<String> fields(JsonNode value) {
        if (value == null || !value.isObject()) {
            return Set.of();
        }
        Set<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private IdempotencyRecoveryResult reject(
            long idempotencyRecordId,
            UUID transactionId,
            AuditActorType actorType,
            String actorId,
            IdempotencyRecoveryDecision decision,
            Instant attemptedAt
    ) {
        appendAudit(
                idempotencyRecordId,
                transactionId,
                actorType,
                actorId,
                decision,
                IdempotencyRecoveryAuditResult.REJECTED,
                attemptedAt
        );
        return IdempotencyRecoveryResult.rejected(
                idempotencyRecordId,
                transactionId,
                decision
        );
    }

    private void appendAudit(
            long idempotencyRecordId,
            UUID transactionId,
            AuditActorType actorType,
            String actorId,
            IdempotencyRecoveryDecision decision,
            IdempotencyRecoveryAuditResult result,
            Instant attemptedAt
    ) {
        recoveryAuditRepository.insert(
                IdempotencyRecoveryAuditLog.create(
                        idempotencyRecordId,
                        transactionId,
                        actorType,
                        actorId,
                        decision,
                        result,
                        attemptedAt
                )
        );
    }
}
