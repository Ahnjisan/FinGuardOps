package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.FraudCaseAssigneeChangeRequest;
import com.aifds.backend.fraudcase.dto.FraudCaseStatusChangeRequest;
import com.aifds.backend.fraudcase.dto.FraudCaseResolutionRequest;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FraudCaseWorkflowValidator {

    public static final String REQUEST_REQUIRED = "REQUEST_REQUIRED";
    public static final String REQUIRED_FIELD = "REQUIRED_FIELD";
    public static final String INVALID_UUID_FORMAT = "INVALID_UUID_FORMAT";
    public static final String INVALID_UUID_VERSION = "INVALID_UUID_VERSION";
    public static final String INVALID_UUID_VARIANT = "INVALID_UUID_VARIANT";
    public static final String UNSUPPORTED_CASE_STATUS =
            "UNSUPPORTED_CASE_STATUS";
    public static final String UNSUPPORTED_REASON_CODE =
            "UNSUPPORTED_REASON_CODE";
    public static final String INVALID_EXPECTED_VERSION =
            "INVALID_EXPECTED_VERSION";
    public static final String INVALID_ASSIGNEE_REF =
            "INVALID_ASSIGNEE_REF";
    public static final String ASSIGNEE_COMMAND_REQUIRED =
            "ASSIGNEE_COMMAND_REQUIRED";
    public static final String REASON_CODE_MISMATCH =
            "REASON_CODE_MISMATCH";
    public static final String ASSIGNEE_NOT_ALLOWED =
            "ASSIGNEE_NOT_ALLOWED";
    public static final String FINAL_DISPOSITION_REQUIRED =
            "FINAL_DISPOSITION_REQUIRED";
    public static final String UNSUPPORTED_FINAL_DISPOSITION =
            "UNSUPPORTED_FINAL_DISPOSITION";

    private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Set<AuditReasonCode> WORKFLOW_REASON_CODES = Set.of(
            AuditReasonCode.CASE_REVIEW_STARTED,
            AuditReasonCode.CASE_ADDITIONAL_INFORMATION_REQUESTED,
            AuditReasonCode.CASE_REVIEW_RESUMED,
            AuditReasonCode.CASE_ASSIGNEE_ASSIGNED,
            AuditReasonCode.CASE_ASSIGNEE_CHANGED,
            AuditReasonCode.CASE_ASSIGNEE_RELEASED
    );

    public FraudCaseWorkflowCommand.StatusChange validateStatus(
            String rawCaseId,
            FraudCaseStatusChangeRequest request
    ) {
        if (request == null) {
            throw format("$", REQUEST_REQUIRED, "Case status request is required");
        }
        require(request.targetStatus(), "targetStatus");
        require(request.reasonCode(), "reasonCode");
        require(request.expectedVersion(), "expectedVersion");
        if (request.expectedVersion() < 0) {
            throw format(
                    "expectedVersion",
                    INVALID_EXPECTED_VERSION,
                    "expectedVersion must be zero or greater"
            );
        }
        if (request.assigneeRefPresent() && request.assigneeRef() != null) {
            validateAssigneeRef(request.assigneeRef());
        }
        return new FraudCaseWorkflowCommand.StatusChange(
                parseUuid("caseId", rawCaseId),
                parseStatus(request.targetStatus()),
                request.assigneeRefPresent(),
                request.assigneeRef(),
                parseReasonCode(request.reasonCode()),
                request.expectedVersion()
        );
    }

    public FraudCaseWorkflowCommand.AssigneeChange validateAssignee(
            String rawCaseId,
            FraudCaseAssigneeChangeRequest request
    ) {
        if (request == null) {
            throw format(
                    "$", REQUEST_REQUIRED, "Case assignee request is required"
            );
        }
        if (!request.assigneeRefPresent()) {
            throw format(
                    "assigneeRef",
                    ASSIGNEE_COMMAND_REQUIRED,
                    "assigneeRef must be explicitly provided"
            );
        }
        require(request.reasonCode(), "reasonCode");
        require(request.expectedVersion(), "expectedVersion");
        if (request.expectedVersion() < 0) {
            throw format(
                    "expectedVersion",
                    INVALID_EXPECTED_VERSION,
                    "expectedVersion must be zero or greater"
            );
        }
        if (request.assigneeRef() != null) {
            validateAssigneeRef(request.assigneeRef());
        }
        return new FraudCaseWorkflowCommand.AssigneeChange(
                parseUuid("caseId", rawCaseId),
                request.assigneeRef(),
                parseReasonCode(request.reasonCode()),
                request.expectedVersion()
        );
    }

    public FraudCaseWorkflowCommand.Resolution validateResolution(
            String rawCaseId,
            FraudCaseResolutionRequest request
    ) {
        UUID caseId = parseUuid("caseId", rawCaseId);
        if (request == null) {
            throw format(
                    "$", REQUEST_REQUIRED, "Case resolution request is required"
            );
        }
        if (request.finalDisposition() == null) {
            throw domain(
                    "finalDisposition",
                    FINAL_DISPOSITION_REQUIRED,
                    "finalDisposition is required"
            );
        }
        require(request.reasonCode(), "reasonCode");
        require(request.expectedVersion(), "expectedVersion");
        if (request.expectedVersion() < 0) {
            throw format(
                    "expectedVersion",
                    INVALID_EXPECTED_VERSION,
                    "expectedVersion must be zero or greater"
            );
        }
        return new FraudCaseWorkflowCommand.Resolution(
                caseId,
                request.finalDisposition(),
                request.reasonCode(),
                request.expectedVersion()
        );
    }

    public FraudCaseFinalDisposition parseResolutionDisposition(String value) {
        try {
            return FraudCaseFinalDisposition.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw domain(
                    "finalDisposition",
                    UNSUPPORTED_FINAL_DISPOSITION,
                    "finalDisposition is not supported"
            );
        }
    }

    public AuditReasonCode parseResolutionReason(String value) {
        AuditReasonCode reasonCode;
        try {
            reasonCode = AuditReasonCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw domain(
                    "reasonCode",
                    UNSUPPORTED_REASON_CODE,
                    "reasonCode is not supported"
            );
        }
        if (reasonCode != AuditReasonCode.CASE_RESOLUTION_COMPLETED) {
            throw domain(
                    "reasonCode",
                    REASON_CODE_MISMATCH,
                    "reasonCode does not match case resolution"
            );
        }
        return reasonCode;
    }

    public void requireReason(
            AuditReasonCode actual,
            AuditReasonCode expected
    ) {
        if (actual != expected) {
            throw domain(
                    "reasonCode",
                    REASON_CODE_MISMATCH,
                    "reasonCode does not match the requested case change"
            );
        }
    }

    public void rejectStatusAssigneeCombination(
            boolean assigneeRefPresent
    ) {
        if (assigneeRefPresent) {
            throw domain(
                    "assigneeRef",
                    ASSIGNEE_NOT_ALLOWED,
                    "assigneeRef is not allowed for this status transition"
            );
        }
    }

    private void validateAssigneeRef(String value) {
        if (value.length() != 36
                || !CANONICAL_UUID_V4.matcher(value).matches()) {
            throw domain(
                    "assigneeRef",
                    INVALID_ASSIGNEE_REF,
                    "assigneeRef must be a canonical lowercase UUID v4"
            );
        }
    }

    private UUID parseUuid(String field, String value) {
        if (value == null || !CANONICAL_UUID_V4.matcher(value).matches()) {
            throw format(
                    field,
                    INVALID_UUID_FORMAT,
                    field + " must use the canonical lowercase UUID v4 format"
            );
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw format(field, INVALID_UUID_FORMAT, field + " must be a UUID");
        }
        if (uuid.version() != 4) {
            throw format(
                    field,
                    INVALID_UUID_VERSION,
                    field + " must be a UUID version 4"
            );
        }
        if (uuid.variant() != 2) {
            throw format(
                    field,
                    INVALID_UUID_VARIANT,
                    field + " must use the RFC 4122 variant"
            );
        }
        return uuid;
    }

    private FraudCaseStatus parseStatus(String value) {
        try {
            return FraudCaseStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "targetStatus",
                    UNSUPPORTED_CASE_STATUS,
                    "targetStatus is not supported"
            );
        }
    }

    private AuditReasonCode parseReasonCode(String value) {
        AuditReasonCode reasonCode;
        try {
            reasonCode = AuditReasonCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "reasonCode",
                    UNSUPPORTED_REASON_CODE,
                    "reasonCode is not supported"
            );
        }
        if (!WORKFLOW_REASON_CODES.contains(reasonCode)) {
            throw format(
                    "reasonCode",
                    UNSUPPORTED_REASON_CODE,
                    "reasonCode is not supported"
            );
        }
        return reasonCode;
    }

    private void require(Object value, String field) {
        if (value == null) {
            throw format(field, REQUIRED_FIELD, field + " is required");
        }
    }

    private FraudCaseValidationException format(
            String field,
            String code,
            String reason
    ) {
        return new FraudCaseValidationException(
                FraudCaseValidationType.FORMAT, field, code, reason
        );
    }

    private FraudCaseValidationException domain(
            String field,
            String code,
            String reason
    ) {
        return new FraudCaseValidationException(
                FraudCaseValidationType.DOMAIN, field, code, reason
        );
    }
}
