package com.aifds.backend.audit.entity;

import com.aifds.backend.audit.validation.AuditJsonPayloadGuard;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Immutable
@Table(
        name = "audit_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_audit_log_audit_id",
                columnNames = "audit_id"
        )
)
public class AuditLog {

    public static final String SYSTEM_ACTOR_ID = "finguardops-backend";

    private static final Pattern USER_ACTOR_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "audit_id", nullable = false, updatable = false)
    private UUID auditId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "actor_type",
            nullable = false,
            length = 16,
            updatable = false
    )
    private AuditActorType actorType;

    @Column(
            name = "actor_id",
            nullable = false,
            length = 128,
            updatable = false
    )
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "action",
            nullable = false,
            length = 64,
            updatable = false
    )
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "reason_code",
            nullable = false,
            length = 64,
            updatable = false
    )
    private AuditReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "target_type",
            nullable = false,
            length = 32,
            updatable = false
    )
    private AuditTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "before_value_summary",
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode beforeValueSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "after_value_summary",
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode afterValueSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode metadata;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected AuditLog() {
    }

    private AuditLog(
            UUID auditId,
            AuditActorType actorType,
            String actorId,
            AuditAction action,
            AuditReasonCode reasonCode,
            AuditTargetType targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            String traceId,
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata,
            Instant changedAt
    ) {
        AuditJsonPayloadGuard.validatePayloads(
                beforeValueSummary,
                afterValueSummary,
                metadata
        );
        this.auditId = auditId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.reasonCode = reasonCode;
        this.targetType = targetType;
        this.targetId = targetId;
        this.transactionId = transactionId;
        this.caseId = caseId;
        this.traceId = traceId;
        this.beforeValueSummary = copy(beforeValueSummary);
        this.afterValueSummary = copy(afterValueSummary);
        this.metadata = copy(metadata);
        this.changedAt = changedAt;
        validateInvariants();
    }

    public static AuditLog create(
            UUID auditId,
            AuditActorType actorType,
            String actorId,
            AuditAction action,
            AuditReasonCode reasonCode,
            AuditTargetType targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            String traceId,
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata,
            Instant changedAt
    ) {
        return new AuditLog(
                auditId,
                actorType,
                actorId,
                action,
                reasonCode,
                targetType,
                targetId,
                transactionId,
                caseId,
                traceId,
                beforeValueSummary,
                afterValueSummary,
                metadata,
                changedAt
        );
    }

    @PrePersist
    private void validateInvariants() {
        requireUuidV4(auditId, "auditId");
        Objects.requireNonNull(actorType, "actorType must not be null");
        validateActorId();
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        requireUuidV4(targetId, "targetId");
        validateContextId(transactionId, "transactionId");
        validateContextId(caseId, "caseId");
        validateActionReason();
        validateTargetContext();
        validateTraceId();
        validateJsonPayload();
        requireMicrosecondInstant(changedAt);
    }

    @PreUpdate
    private void rejectUpdate() {
        throw new IllegalStateException("AuditLog is append-only");
    }

    @PreRemove
    private void rejectRemove() {
        throw new IllegalStateException("AuditLog cannot be removed");
    }

    private void validateActorId() {
        if (actorId == null) {
            throw new NullPointerException("actorId must not be null");
        }
        if (actorType == AuditActorType.SYSTEM) {
            if (!SYSTEM_ACTOR_ID.equals(actorId)) {
                throw new IllegalArgumentException(
                        "SYSTEM actorId must be " + SYSTEM_ACTOR_ID
                );
            }
            return;
        }
        if (!USER_ACTOR_ID_PATTERN.matcher(actorId).matches()) {
            throw new IllegalArgumentException(
                    "USER actorId must be a canonical lowercase UUID v4"
            );
        }
    }

    private void validateActionReason() {
        boolean valid = switch (action) {
            case CASE_CREATED, CASE_TRANSACTION_LINKED ->
                    reasonCode
                            == AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY;
            case TRANSACTION_RISK_RESPONSE_APPLIED ->
                    reasonCode
                            == AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY;
            case TRANSACTION_STATUS_CHANGED ->
                    reasonCode
                            == AuditReasonCode
                            .TRANSACTION_FINALIZED_BY_RISK_POLICY;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "reasonCode does not match action"
            );
        }
    }

    private void validateTargetContext() {
        boolean valid = switch (action) {
            case CASE_CREATED, CASE_TRANSACTION_LINKED ->
                    targetType == AuditTargetType.FRAUD_CASE
                            && caseId != null
                            && transactionId != null
                            && targetId.equals(caseId);
            case TRANSACTION_RISK_RESPONSE_APPLIED,
                    TRANSACTION_STATUS_CHANGED ->
                    targetType == AuditTargetType.FINANCIAL_TRANSACTION
                            && transactionId != null
                            && targetId.equals(transactionId);
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "target and context identifiers do not match action"
            );
        }
    }

    private void validateTraceId() {
        if (traceId != null && !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            throw new IllegalArgumentException(
                    "traceId must match the approved 8 to 64 character format"
            );
        }
    }

    private void validateJsonPayload() {
        AuditJsonPayloadGuard.validatePayloads(
                beforeValueSummary,
                afterValueSummary,
                metadata
        );
    }

    private void validateContextId(UUID value, String fieldName) {
        if (value != null) {
            requireUuidV4(value, fieldName);
        }
    }

    private void requireUuidV4(UUID value, String fieldName) {
        UUID validated = Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );
        if (validated.version() != 4 || validated.variant() != 2) {
            throw new IllegalArgumentException(
                    fieldName + " must be a UUID v4"
            );
        }
    }

    private void requireMicrosecondInstant(Instant value) {
        Instant validated = Objects.requireNonNull(
                value,
                "changedAt must not be null"
        );
        if (validated.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "changedAt must have microsecond precision"
            );
        }
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    public Long getId() {
        return id;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditReasonCode getReasonCode() {
        return reasonCode;
    }

    public AuditTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getTraceId() {
        return traceId;
    }

    public JsonNode getBeforeValueSummary() {
        return copy(beforeValueSummary);
    }

    public JsonNode getAfterValueSummary() {
        return copy(afterValueSummary);
    }

    public JsonNode getMetadata() {
        return copy(metadata);
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
