package com.aifds.backend.fraudcase.command;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;

import java.util.UUID;

public final class FraudCaseWorkflowCommand {

    private FraudCaseWorkflowCommand() {
    }

    public record StatusChange(
            UUID caseId,
            FraudCaseStatus targetStatus,
            boolean assigneeRefPresent,
            String assigneeRef,
            AuditReasonCode reasonCode,
            long expectedVersion
    ) {
    }

    public record AssigneeChange(
            UUID caseId,
            String assigneeRef,
            AuditReasonCode reasonCode,
            long expectedVersion
    ) {
    }

    public record Resolution(
            UUID caseId,
            String finalDisposition,
            String reasonCode,
            long expectedVersion
    ) {
    }
}
