package com.aifds.backend.audit.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditTargetType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PersistedAuditLog(
        UUID auditId,
        AuditAction action,
        AuditTargetType targetType,
        UUID targetId,
        UUID transactionId,
        UUID caseId,
        Instant changedAt
) {

    public PersistedAuditLog {
        Objects.requireNonNull(auditId, "auditId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
    }
}
