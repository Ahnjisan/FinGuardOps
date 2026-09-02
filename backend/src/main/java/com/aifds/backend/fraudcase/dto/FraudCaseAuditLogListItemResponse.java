package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;

import java.time.Instant;
import java.util.UUID;

public record FraudCaseAuditLogListItemResponse(
        AuditAction action,
        AuditReasonCode reasonCode,
        AuditActorType actorType,
        Instant changedAt,
        Summary beforeSummary,
        Summary afterSummary,
        Metadata metadata
) {

    public sealed interface Summary permits CaseStatusSummary,
            LinkedSummary, WorkflowSummary, ResolutionSummary {
    }

    public record CaseStatusSummary(
            FraudCaseStatus caseStatus
    ) implements Summary {
    }

    public record LinkedSummary(boolean linked) implements Summary {
    }

    public record WorkflowSummary(
            FraudCaseStatus caseStatus,
            UUID assigneeRef
    ) implements Summary {
    }

    public record ResolutionSummary(
            FraudCaseStatus caseStatus,
            UUID assigneeRef,
            FraudCaseFinalDisposition finalDisposition
    ) implements Summary {
    }

    public sealed interface Metadata permits EmptyMetadata, NoteMetadata {
    }

    public record EmptyMetadata() implements Metadata {
    }

    public record NoteMetadata(UUID noteId) implements Metadata {
    }
}
