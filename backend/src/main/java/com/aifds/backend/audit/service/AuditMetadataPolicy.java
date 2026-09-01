package com.aifds.backend.audit.service;

import com.aifds.backend.audit.validation.AuditJsonPayloadGuard;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
public final class AuditMetadataPolicy {

    private static final Set<String> DETECTION_METADATA_FIELDS = Set.of(
            "detectionResultId",
            "detectionResultVersion"
    );
    private static final Set<String> TRANSACTION_METADATA_FIELDS = Set.of(
            "sourceRiskLevel",
            "detectionResultId",
            "detectionResultVersion"
    );

    public void validate(AuditLogDraft draft) {
        JsonNode beforeValueSummary = draft.beforeValueSummary();
        JsonNode afterValueSummary = draft.afterValueSummary();
        JsonNode metadata = draft.metadata();
        AuditJsonPayloadGuard.validatePayloads(
                beforeValueSummary,
                afterValueSummary,
                metadata
        );

        switch (draft.action()) {
            case CASE_CREATED -> validateCaseCreated(
                    beforeValueSummary,
                    afterValueSummary,
                    metadata
            );
            case CASE_TRANSACTION_LINKED -> validateCaseLinked(
                    beforeValueSummary,
                    afterValueSummary,
                    metadata
            );
            case CASE_STATUS_CHANGED -> validateCaseStatusChanged(
                    draft.reasonCode(),
                    beforeValueSummary,
                    afterValueSummary,
                    metadata
            );
            case CASE_ASSIGNEE_CHANGED -> validateCaseAssigneeChanged(
                    draft.reasonCode(),
                    beforeValueSummary,
                    afterValueSummary,
                    metadata
            );
            case TRANSACTION_RISK_RESPONSE_APPLIED ->
                    validateRiskResponseApplied(
                            beforeValueSummary,
                            afterValueSummary,
                            metadata
                    );
            case TRANSACTION_STATUS_CHANGED ->
                    validateTransactionStatusChanged(
                            beforeValueSummary,
                            afterValueSummary,
                            metadata
                    );
        }
    }

    private void validateCaseCreated(
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata
    ) {
        requireNull(beforeValueSummary, "beforeValueSummary");
        requireExactFields(
                afterValueSummary,
                "afterValueSummary",
                Set.of("caseStatus")
        );
        requireEnum(
                afterValueSummary,
                "caseStatus",
                FraudCaseStatus.class
        );
        if (!FraudCaseStatus.OPEN.name().equals(
                afterValueSummary.get("caseStatus").textValue()
        )) {
            throw new IllegalArgumentException(
                    "CASE_CREATED caseStatus must be OPEN"
            );
        }
        validateMetadata(metadata, DETECTION_METADATA_FIELDS);
    }

    private void validateCaseLinked(
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata
    ) {
        requireNull(beforeValueSummary, "beforeValueSummary");
        requireExactFields(
                afterValueSummary,
                "afterValueSummary",
                Set.of("linked")
        );
        JsonNode linked = afterValueSummary.get("linked");
        if (!linked.isBoolean() || !linked.booleanValue()) {
            throw new IllegalArgumentException(
                    "CASE_TRANSACTION_LINKED linked must be true"
            );
        }
        validateMetadata(metadata, DETECTION_METADATA_FIELDS);
    }

    private void validateCaseStatusChanged(
            com.aifds.backend.audit.entity.AuditReasonCode reasonCode,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        validateCaseSnapshot(before, "beforeValueSummary");
        validateCaseSnapshot(after, "afterValueSummary");
        requireEmptyMetadata(metadata);

        String beforeStatus = before.get("caseStatus").textValue();
        String afterStatus = after.get("caseStatus").textValue();
        String beforeAssignee = textOrNull(before, "assigneeRef");
        String afterAssignee = textOrNull(after, "assigneeRef");
        boolean valid = switch (reasonCode) {
            case CASE_REVIEW_STARTED ->
                    "OPEN".equals(beforeStatus)
                            && beforeAssignee == null
                            && "IN_REVIEW".equals(afterStatus)
                            && afterAssignee != null;
            case CASE_ADDITIONAL_INFORMATION_REQUESTED ->
                    "IN_REVIEW".equals(beforeStatus)
                            && "ADDITIONAL_INFORMATION_REQUIRED"
                            .equals(afterStatus)
                            && beforeAssignee != null
                            && beforeAssignee.equals(afterAssignee);
            case CASE_REVIEW_RESUMED ->
                    "ADDITIONAL_INFORMATION_REQUIRED".equals(beforeStatus)
                            && "IN_REVIEW".equals(afterStatus)
                            && beforeAssignee != null
                            && beforeAssignee.equals(afterAssignee);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "case status snapshot does not match reasonCode"
            );
        }
    }

    private void validateCaseAssigneeChanged(
            com.aifds.backend.audit.entity.AuditReasonCode reasonCode,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        validateCaseSnapshot(before, "beforeValueSummary");
        validateCaseSnapshot(after, "afterValueSummary");
        requireEmptyMetadata(metadata);

        String beforeStatus = before.get("caseStatus").textValue();
        String afterStatus = after.get("caseStatus").textValue();
        String beforeAssignee = textOrNull(before, "assigneeRef");
        String afterAssignee = textOrNull(after, "assigneeRef");
        boolean editableState = beforeStatus.equals(afterStatus)
                && ("IN_REVIEW".equals(beforeStatus)
                || "ADDITIONAL_INFORMATION_REQUIRED".equals(beforeStatus));
        boolean valid = editableState && switch (reasonCode) {
            case CASE_ASSIGNEE_ASSIGNED ->
                    "ADDITIONAL_INFORMATION_REQUIRED".equals(beforeStatus)
                            && beforeAssignee == null
                            && afterAssignee != null;
            case CASE_ASSIGNEE_CHANGED ->
                    beforeAssignee != null
                            && afterAssignee != null
                            && !beforeAssignee.equals(afterAssignee);
            case CASE_ASSIGNEE_RELEASED ->
                    "ADDITIONAL_INFORMATION_REQUIRED".equals(beforeStatus)
                            && beforeAssignee != null
                            && afterAssignee == null;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "case assignee snapshot does not match reasonCode"
            );
        }
    }

    private void validateCaseSnapshot(JsonNode value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null"
            );
        }
        Set<String> actualFields = fields(value);
        if (!actualFields.equals(Set.of("caseStatus"))
                && !actualFields.equals(Set.of("caseStatus", "assigneeRef"))) {
            throw new IllegalArgumentException(
                    fieldName + " fields do not match the case workflow action"
            );
        }
        requireEnum(value, "caseStatus", FraudCaseStatus.class);
        if (value.has("assigneeRef")) {
            requireUuidV4(value, "assigneeRef");
        }
    }

    private String textOrNull(JsonNode value, String field) {
        return value.has(field) ? value.get(field).textValue() : null;
    }

    private void requireEmptyMetadata(JsonNode metadata) {
        if (!fields(metadata).isEmpty()) {
            throw new IllegalArgumentException(
                    "case workflow metadata must be empty"
            );
        }
    }

    private void validateRiskResponseApplied(
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata
    ) {
        if (beforeValueSummary != null) {
            requireExactFields(
                    beforeValueSummary,
                    "beforeValueSummary",
                    Set.of("riskResponseOutcome")
            );
            requireEnum(
                    beforeValueSummary,
                    "riskResponseOutcome",
                    RiskResponseOutcome.class
            );
        }
        requireExactFields(
                afterValueSummary,
                "afterValueSummary",
                Set.of("riskResponseOutcome")
        );
        requireEnum(
                afterValueSummary,
                "riskResponseOutcome",
                RiskResponseOutcome.class
        );
        validateMetadata(metadata, TRANSACTION_METADATA_FIELDS);
    }

    private void validateTransactionStatusChanged(
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata
    ) {
        requireExactFields(
                beforeValueSummary,
                "beforeValueSummary",
                Set.of("processingStatus")
        );
        requireExactFields(
                afterValueSummary,
                "afterValueSummary",
                Set.of("processingStatus")
        );
        requireEnum(
                beforeValueSummary,
                "processingStatus",
                TransactionProcessingStatus.class
        );
        requireEnum(
                afterValueSummary,
                "processingStatus",
                TransactionProcessingStatus.class
        );
        validateMetadata(metadata, TRANSACTION_METADATA_FIELDS);
    }

    private void validateMetadata(
            JsonNode metadata,
            Set<String> allowedFields
    ) {
        Set<String> actualFields = fields(metadata);
        if (!allowedFields.containsAll(actualFields)) {
            throw new IllegalArgumentException(
                    "metadata contains unsupported fields"
            );
        }
        if (metadata.has("detectionResultId")) {
            requireUuidV4(metadata, "detectionResultId");
        }
        if (metadata.has("detectionResultVersion")) {
            requirePositiveInteger(metadata, "detectionResultVersion");
        }
        if (metadata.has("sourceRiskLevel")) {
            requireEnum(metadata, "sourceRiskLevel", RiskLevel.class);
        }
    }

    private void requireNull(JsonNode value, String fieldName) {
        if (value != null) {
            throw new IllegalArgumentException(
                    fieldName + " must be null"
            );
        }
    }

    private void requireExactFields(
            JsonNode value,
            String fieldName,
            Set<String> expectedFields
    ) {
        if (value == null || !expectedFields.equals(fields(value))) {
            throw new IllegalArgumentException(
                    fieldName + " fields do not match the audit action"
            );
        }
    }

    private Set<String> fields(JsonNode value) {
        Set<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private <E extends Enum<E>> void requireEnum(
            JsonNode root,
            String field,
            Class<E> enumType
    ) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    field + " must be a supported " + enumType.getSimpleName()
            );
        }
        try {
            Enum.valueOf(enumType, value.textValue());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    field + " must be a supported " + enumType.getSimpleName(),
                    exception
            );
        }
    }

    private void requireUuidV4(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical lowercase UUID v4"
            );
        }
        String text = value.textValue();
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equals(text)
                    || uuid.version() != 4
                    || uuid.variant() != 2) {
                throw new IllegalArgumentException(
                        field + " must be a canonical lowercase UUID v4"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    field + " must be a canonical lowercase UUID v4",
                    exception
            );
        }
    }

    private void requirePositiveInteger(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 1) {
            throw new IllegalArgumentException(
                    field + " must be a positive integer"
            );
        }
    }
}
