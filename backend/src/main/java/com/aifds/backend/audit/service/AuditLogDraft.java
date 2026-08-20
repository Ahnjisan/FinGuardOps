package com.aifds.backend.audit.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.validation.AuditJsonPayloadGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.UUID;

public record AuditLogDraft(
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
        JsonNode metadata
) {

    public AuditLogDraft {
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        AuditJsonPayloadGuard.validatePayloads(
                beforeValueSummary,
                afterValueSummary,
                metadata
        );
        beforeValueSummary = copy(beforeValueSummary);
        afterValueSummary = copy(afterValueSummary);
        metadata = copy(metadata);
    }

    @Override
    public JsonNode beforeValueSummary() {
        return copy(beforeValueSummary);
    }

    @Override
    public JsonNode afterValueSummary() {
        return copy(afterValueSummary);
    }

    @Override
    public JsonNode metadata() {
        return copy(metadata);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
