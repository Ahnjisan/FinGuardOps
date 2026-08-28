package com.aifds.backend.idempotency.entity;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Immutable
@Table(
        name = "idempotency_recovery_audit_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotency_recovery_audit_id",
                columnNames = "audit_id"
        )
)
public class IdempotencyRecoveryAuditLog {

    private static final Pattern USER_ACTOR_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "audit_id", nullable = false, updatable = false)
    private UUID auditId;

    @Column(
            name = "idempotency_record_id",
            nullable = false,
            updatable = false
    )
    private long idempotencyRecordId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

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
            name = "recovery_decision",
            nullable = false,
            length = 48,
            updatable = false
    )
    private IdempotencyRecoveryDecision recoveryDecision;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "audit_result",
            nullable = false,
            length = 16,
            updatable = false
    )
    private IdempotencyRecoveryAuditResult auditResult;

    @Column(
            name = "attempted_at",
            nullable = false,
            updatable = false
    )
    private Instant attemptedAt;

    protected IdempotencyRecoveryAuditLog() {
    }

    private IdempotencyRecoveryAuditLog(
            UUID auditId,
            long idempotencyRecordId,
            UUID transactionId,
            AuditActorType actorType,
            String actorId,
            IdempotencyRecoveryDecision recoveryDecision,
            IdempotencyRecoveryAuditResult auditResult,
            Instant attemptedAt
    ) {
        this.auditId = auditId;
        this.idempotencyRecordId = idempotencyRecordId;
        this.transactionId = transactionId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.recoveryDecision = recoveryDecision;
        this.auditResult = auditResult;
        this.attemptedAt = attemptedAt;
        validateInvariants();
    }

    public static IdempotencyRecoveryAuditLog create(
            long idempotencyRecordId,
            UUID transactionId,
            AuditActorType actorType,
            String actorId,
            IdempotencyRecoveryDecision recoveryDecision,
            IdempotencyRecoveryAuditResult auditResult,
            Instant attemptedAt
    ) {
        return new IdempotencyRecoveryAuditLog(
                UUID.randomUUID(),
                idempotencyRecordId,
                transactionId,
                actorType,
                actorId,
                recoveryDecision,
                auditResult,
                attemptedAt
        );
    }

    public static void validateActor(
            AuditActorType actorType,
            String actorId
    ) {
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (actorType == AuditActorType.SYSTEM) {
            if (!AuditLog.SYSTEM_ACTOR_ID.equals(actorId)) {
                throw new IllegalArgumentException(
                        "SYSTEM actorId must be " + AuditLog.SYSTEM_ACTOR_ID
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

    @PrePersist
    private void validateInvariants() {
        requireUuidV4(auditId, "auditId");
        if (idempotencyRecordId < 1) {
            throw new IllegalArgumentException(
                    "idempotencyRecordId must be positive"
            );
        }
        if (transactionId != null) {
            requireUuidV4(transactionId, "transactionId");
        }
        validateActor(actorType, actorId);
        Objects.requireNonNull(
                recoveryDecision,
                "recoveryDecision must not be null"
        );
        Objects.requireNonNull(auditResult, "auditResult must not be null");
        validateDecisionResult();
        Instant timestamp = Objects.requireNonNull(
                attemptedAt,
                "attemptedAt must not be null"
        );
        if (timestamp.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "attemptedAt must have microsecond precision"
            );
        }
    }

    private void validateDecisionResult() {
        boolean valid = switch (auditResult) {
            case RECOVERED -> recoveryDecision
                    == IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP;
            case REJECTED -> recoveryDecision
                    != IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP
                    && recoveryDecision
                    != IdempotencyRecoveryDecision.INTERNAL_FAILURE;
            case FAILED -> recoveryDecision
                    == IdempotencyRecoveryDecision.INTERNAL_FAILURE;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "recoveryDecision and auditResult do not match"
            );
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

    @PreUpdate
    private void rejectUpdate() {
        throw new IllegalStateException(
                "IdempotencyRecoveryAuditLog is append-only"
        );
    }

    @PreRemove
    private void rejectRemove() {
        throw new IllegalStateException(
                "IdempotencyRecoveryAuditLog cannot be removed"
        );
    }

    public Long getId() {
        return id;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public long getIdempotencyRecordId() {
        return idempotencyRecordId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public IdempotencyRecoveryDecision getRecoveryDecision() {
        return recoveryDecision;
    }

    public IdempotencyRecoveryAuditResult getAuditResult() {
        return auditResult;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
