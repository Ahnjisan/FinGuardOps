package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditMetadataPolicy;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.CaseStatusSummary;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.EmptyMetadata;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.LinkedSummary;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.NoteMetadata;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.ResolutionSummary;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.Summary;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse.WorkflowSummary;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseConsistencyException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FraudCaseAuditLogMapper {

    private static final String INCONSISTENT_AUDIT_MESSAGE =
            "Stored fraud case audit log is inconsistent";
    private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    private final AuditMetadataPolicy metadataPolicy;

    public FraudCaseAuditLogMapper(AuditMetadataPolicy metadataPolicy) {
        this.metadataPolicy = metadataPolicy;
    }

    public FraudCaseAuditLogListItemResponse toResponse(
            AuditLog auditLog,
            UUID requestedCaseId
    ) {
        try {
            return mapValidated(
                    Objects.requireNonNull(auditLog),
                    requireUuidV4(requestedCaseId)
            );
        } catch (RuntimeException exception) {
            throw new FraudCaseConsistencyException(
                    INCONSISTENT_AUDIT_MESSAGE
            );
        }
    }

    private FraudCaseAuditLogListItemResponse mapValidated(
            AuditLog auditLog,
            UUID requestedCaseId
    ) {
        AuditAction action = Objects.requireNonNull(auditLog.getAction());
        AuditReasonCode reason = Objects.requireNonNull(
                auditLog.getReasonCode()
        );
        AuditActorType actorType = Objects.requireNonNull(
                auditLog.getActorType()
        );
        requireUuidV4(auditLog.getAuditId());
        validateActionReason(action, reason);
        validateContext(auditLog, requestedCaseId, action);
        validateActor(actorType, auditLog.getActorId());
        Instant changedAt = Objects.requireNonNull(auditLog.getChangedAt());
        if (changedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException();
        }

        JsonNode before = auditLog.getBeforeValueSummary();
        JsonNode after = auditLog.getAfterValueSummary();
        JsonNode metadata = auditLog.getMetadata();
        metadataPolicy.validate(new AuditLogDraft(
                actorType,
                auditLog.getActorId(),
                action,
                reason,
                auditLog.getTargetType(),
                auditLog.getTargetId(),
                auditLog.getTransactionId(),
                auditLog.getCaseId(),
                auditLog.getTraceId(),
                before,
                after,
                metadata
        ));

        Summary beforeProjection = switch (action) {
            case CASE_CREATED, CASE_TRANSACTION_LINKED, CASE_NOTE_CREATED ->
                    null;
            case CASE_STATUS_CHANGED, CASE_ASSIGNEE_CHANGED ->
                    workflowSummary(before);
            case CASE_RESOLVED -> workflowSummary(before);
            default -> throw new IllegalArgumentException();
        };
        Summary afterProjection = switch (action) {
            case CASE_CREATED -> new CaseStatusSummary(
                    status(after, "caseStatus")
            );
            case CASE_TRANSACTION_LINKED -> new LinkedSummary(
                    after.get("linked").booleanValue()
            );
            case CASE_STATUS_CHANGED, CASE_ASSIGNEE_CHANGED ->
                    workflowSummary(after);
            case CASE_RESOLVED -> new ResolutionSummary(
                    status(after, "caseStatus"),
                    requiredUuid(after, "assigneeRef"),
                    disposition(after, "finalDisposition")
            );
            case CASE_NOTE_CREATED -> null;
            default -> throw new IllegalArgumentException();
        };

        return new FraudCaseAuditLogListItemResponse(
                action,
                reason,
                actorType,
                changedAt,
                beforeProjection,
                afterProjection,
                action == AuditAction.CASE_NOTE_CREATED
                        ? new NoteMetadata(requiredUuid(metadata, "noteId"))
                        : new EmptyMetadata()
        );
    }

    private void validateActionReason(
            AuditAction action,
            AuditReasonCode reason
    ) {
        boolean valid = switch (action) {
            case CASE_CREATED, CASE_TRANSACTION_LINKED ->
                    reason == AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY;
            case CASE_STATUS_CHANGED -> switch (reason) {
                case CASE_REVIEW_STARTED,
                        CASE_ADDITIONAL_INFORMATION_REQUESTED,
                        CASE_REVIEW_RESUMED -> true;
                default -> false;
            };
            case CASE_ASSIGNEE_CHANGED -> switch (reason) {
                case CASE_ASSIGNEE_ASSIGNED,
                        CASE_ASSIGNEE_CHANGED,
                        CASE_ASSIGNEE_RELEASED -> true;
                default -> false;
            };
            case CASE_RESOLVED ->
                    reason == AuditReasonCode.CASE_RESOLUTION_COMPLETED;
            case CASE_NOTE_CREATED -> reason
                    == AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException();
        }
    }

    private void validateContext(
            AuditLog auditLog,
            UUID requestedCaseId,
            AuditAction action
    ) {
        if (auditLog.getTargetType() != AuditTargetType.FRAUD_CASE
                || !requestedCaseId.equals(auditLog.getTargetId())
                || !requestedCaseId.equals(auditLog.getCaseId())) {
            throw new IllegalArgumentException();
        }
        requireUuidV4(auditLog.getTargetId());
        requireUuidV4(auditLog.getCaseId());
        if (action == AuditAction.CASE_CREATED
                || action == AuditAction.CASE_TRANSACTION_LINKED) {
            requireUuidV4(auditLog.getTransactionId());
        } else if (auditLog.getTransactionId() != null) {
            throw new IllegalArgumentException();
        }
    }

    private void validateActor(AuditActorType actorType, String actorId) {
        if (actorType == AuditActorType.SYSTEM) {
            if (!AuditLog.SYSTEM_ACTOR_ID.equals(actorId)) {
                throw new IllegalArgumentException();
            }
            return;
        }
        parseCanonicalUuid(actorId);
    }

    private WorkflowSummary workflowSummary(JsonNode value) {
        return new WorkflowSummary(
                status(value, "caseStatus"),
                optionalUuid(value, "assigneeRef")
        );
    }

    private FraudCaseStatus status(JsonNode root, String field) {
        return enumValue(root, field, FraudCaseStatus.class);
    }

    private FraudCaseFinalDisposition disposition(
            JsonNode root,
            String field
    ) {
        return enumValue(root, field, FraudCaseFinalDisposition.class);
    }

    private <E extends Enum<E>> E enumValue(
            JsonNode root,
            String field,
            Class<E> enumType
    ) {
        JsonNode value = Objects.requireNonNull(root).get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException();
        }
        return Enum.valueOf(enumType, value.textValue());
    }

    private UUID optionalUuid(JsonNode root, String field) {
        if (!Objects.requireNonNull(root).has(field)) {
            return null;
        }
        return requiredUuid(root, field);
    }

    private UUID requiredUuid(JsonNode root, String field) {
        JsonNode value = Objects.requireNonNull(root).get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException();
        }
        return parseCanonicalUuid(value.textValue());
    }

    private UUID parseCanonicalUuid(String value) {
        if (value == null || !CANONICAL_UUID_V4.matcher(value).matches()) {
            throw new IllegalArgumentException();
        }
        return requireUuidV4(UUID.fromString(value));
    }

    private UUID requireUuidV4(UUID value) {
        UUID uuid = Objects.requireNonNull(value);
        if (uuid.version() != 4 || uuid.variant() != 2) {
            throw new IllegalArgumentException();
        }
        return uuid;
    }
}
