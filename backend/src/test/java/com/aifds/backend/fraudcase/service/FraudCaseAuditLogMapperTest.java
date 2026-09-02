package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditMetadataPolicy;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse;
import com.aifds.backend.fraudcase.exception.FraudCaseConsistencyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCaseAuditLogMapperTest {

    private static final UUID CASE_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000001"
    );
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "20000000-0000-4000-9000-000000000001"
    );
    private static final UUID ASSIGNEE_A = UUID.fromString(
            "30000000-0000-4000-9000-000000000001"
    );
    private static final UUID ASSIGNEE_B = UUID.fromString(
            "30000000-0000-4000-9000-000000000002"
    );
    private static final UUID NOTE_ID = UUID.fromString(
            "4a000000-0000-4000-9000-000000000001"
    );
    private static final Instant CHANGED_AT =
            Instant.parse("2026-09-01T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FraudCaseAuditLogMapper mapper =
            new FraudCaseAuditLogMapper(new AuditMetadataPolicy());

    @ParameterizedTest
    @EnumSource(value = AuditAction.class, names = {
            "CASE_CREATED", "CASE_TRANSACTION_LINKED",
            "CASE_STATUS_CHANGED", "CASE_ASSIGNEE_CHANGED",
            "CASE_RESOLVED", "CASE_NOTE_CREATED"
    })
    void mapsEveryApprovedActionToTypedProjection(AuditAction action) {
        FraudCaseAuditLogListItemResponse response = mapper.toResponse(
                validLog(action), CASE_ID
        );

        assertThat(response.action()).isEqualTo(action);
        assertThat(response.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(response.changedAt()).isEqualTo(CHANGED_AT);
        if (action == AuditAction.CASE_CREATED
                || action == AuditAction.CASE_TRANSACTION_LINKED) {
            assertThat(response.beforeSummary()).isNull();
        }
        if (action == AuditAction.CASE_NOTE_CREATED) {
            assertThat(response.beforeSummary()).isNull();
            assertThat(response.afterSummary()).isNull();
            assertThat(response.metadata())
                    .isEqualTo(new FraudCaseAuditLogListItemResponse
                            .NoteMetadata(NOTE_ID));
        } else {
            assertThat(response.metadata())
                    .isInstanceOf(FraudCaseAuditLogListItemResponse
                            .EmptyMetadata.class);
        }
    }

    @Test
    void projectsMissingStoredAssigneeAsExplicitNullAndHidesDetectionMetadata() {
        var created = mapper.toResponse(validLog(AuditAction.CASE_CREATED), CASE_ID);
        var started = mapper.toResponse(
                validLog(AuditAction.CASE_STATUS_CHANGED), CASE_ID
        );

        assertThat(((FraudCaseAuditLogListItemResponse.WorkflowSummary)
                started.beforeSummary()).assigneeRef()).isNull();
        assertThat(created.metadata())
                .isInstanceOf(FraudCaseAuditLogListItemResponse.EmptyMetadata.class);
    }

    @Test
    void rejectsWrongReasonContextAndTransactionContractsSafely() {
        AuditLog invalidReason = mockLog(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_RESOLUTION_COMPLETED,
                CASE_ID,
                CASE_ID,
                TRANSACTION_ID,
                null,
                object("caseStatus", "OPEN"),
                detectionMetadata()
        );
        assertSafeFailure(invalidReason);

        AuditLog wrongTarget = mockLog(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                UUID.randomUUID(),
                CASE_ID,
                TRANSACTION_ID,
                null,
                object("caseStatus", "OPEN"),
                detectionMetadata()
        );
        assertSafeFailure(wrongTarget);

        AuditLog missingTransaction = mockLog(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                CASE_ID,
                CASE_ID,
                null,
                null,
                object("caseStatus", "OPEN"),
                detectionMetadata()
        );
        assertSafeFailure(missingTransaction);

        AuditLog unexpectedTransaction = mockLog(
                AuditAction.CASE_NOTE_CREATED,
                AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED,
                CASE_ID,
                CASE_ID,
                TRANSACTION_ID,
                null,
                null,
                object("noteId", NOTE_ID.toString())
        );
        assertSafeFailure(unexpectedTransaction);
    }

    @Test
    void rejectsUnsupportedActionsAndCaseContextMismatch() {
        AuditLog transactionAction = mockLog(
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                CASE_ID,
                CASE_ID,
                TRANSACTION_ID,
                object("processingStatus", "ANALYZED"),
                object("processingStatus", "HELD"),
                object("sourceRiskLevel", "HIGH")
        );
        assertSafeFailure(transactionAction);

        assertThatThrownBy(() -> mapper.toResponse(
                validLog(AuditAction.CASE_CREATED), UUID.randomUUID()
        )).isInstanceOf(FraudCaseConsistencyException.class);
    }

    @Test
    void rejectsMissingExtraNullWrongTypeEnumUuidAndSemanticMismatch() {
        assertInvalidCreated(
                object("caseStatus", "OPEN"),
                object("caseStatus", "OPEN"),
                detectionMetadata()
        );
        assertInvalidCreated(null, object("caseStatus", "CLOSED"), detectionMetadata());
        assertInvalidCreated(null, object("caseStatus", "OPEN").put("extra", true), detectionMetadata());
        assertInvalidCreated(null, objectMapper.createObjectNode().putNull("caseStatus"), detectionMetadata());
        assertInvalidCreated(null, objectMapper.createObjectNode().put("caseStatus", 1), detectionMetadata());
        assertInvalidCreated(null, objectMapper.createObjectNode(), detectionMetadata());
        assertInvalidCreated(null, object("caseStatus", "UNKNOWN"), detectionMetadata());
        assertInvalidCreated(null, object("caseStatus", "OPEN"), object("detectionResultId", "not-a-uuid"));

        AuditLog badNote = mockLog(
                AuditAction.CASE_NOTE_CREATED,
                AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED,
                CASE_ID,
                CASE_ID,
                null,
                null,
                null,
                object("noteId", NOTE_ID.toString().toUpperCase())
        );
        assertSafeFailure(badNote);

        AuditLog semantic = mockLog(
                AuditAction.CASE_STATUS_CHANGED,
                AuditReasonCode.CASE_REVIEW_STARTED,
                CASE_ID,
                CASE_ID,
                null,
                object("caseStatus", "OPEN"),
                workflow("ADDITIONAL_INFORMATION_REQUIRED", ASSIGNEE_A),
                objectMapper.createObjectNode()
        );
        assertSafeFailure(semantic);
    }

    private void assertInvalidCreated(
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        assertSafeFailure(mockLog(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                CASE_ID,
                CASE_ID,
                TRANSACTION_ID,
                before,
                after,
                metadata
        ));
    }

    private void assertSafeFailure(AuditLog auditLog) {
        assertThatThrownBy(() -> mapper.toResponse(auditLog, CASE_ID))
                .isInstanceOf(FraudCaseConsistencyException.class)
                .hasMessage("Stored fraud case audit log is inconsistent")
                .hasMessageNotContaining("caseStatus")
                .hasMessageNotContaining("noteId")
                .hasMessageNotContaining("detectionResultId")
                .hasMessageNotContaining("not-a-uuid")
                .hasMessageNotContaining("raw");
    }

    private AuditLog validLog(AuditAction action) {
        JsonNode before = null;
        JsonNode after;
        JsonNode metadata = objectMapper.createObjectNode();
        AuditReasonCode reason;
        UUID transactionId = null;
        switch (action) {
            case CASE_CREATED -> {
                reason = AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY;
                transactionId = TRANSACTION_ID;
                after = object("caseStatus", "OPEN");
                metadata = detectionMetadata();
            }
            case CASE_TRANSACTION_LINKED -> {
                reason = AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY;
                transactionId = TRANSACTION_ID;
                after = objectMapper.createObjectNode().put("linked", true);
                metadata = detectionMetadata();
            }
            case CASE_STATUS_CHANGED -> {
                reason = AuditReasonCode.CASE_REVIEW_STARTED;
                before = object("caseStatus", "OPEN");
                after = workflow("IN_REVIEW", ASSIGNEE_A);
            }
            case CASE_ASSIGNEE_CHANGED -> {
                reason = AuditReasonCode.CASE_ASSIGNEE_CHANGED;
                before = workflow("IN_REVIEW", ASSIGNEE_A);
                after = workflow("IN_REVIEW", ASSIGNEE_B);
            }
            case CASE_RESOLVED -> {
                reason = AuditReasonCode.CASE_RESOLUTION_COMPLETED;
                before = workflow("IN_REVIEW", ASSIGNEE_A);
                after = workflow("CLOSED", ASSIGNEE_A)
                        .put("finalDisposition", "CONFIRMED_FRAUD");
            }
            case CASE_NOTE_CREATED -> {
                reason = AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED;
                after = null;
                metadata = object("noteId", NOTE_ID.toString());
            }
            default -> throw new IllegalArgumentException();
        }
        return AuditLog.create(
                UUID.randomUUID(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                action,
                reason,
                AuditTargetType.FRAUD_CASE,
                CASE_ID,
                transactionId,
                CASE_ID,
                "trace_stored_secret_01",
                before,
                after,
                metadata,
                CHANGED_AT
        );
    }

    private AuditLog mockLog(
            AuditAction action,
            AuditReasonCode reason,
            UUID targetId,
            UUID caseId,
            UUID transactionId,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        AuditLog log = mock(AuditLog.class);
        when(log.getAction()).thenReturn(action);
        when(log.getAuditId()).thenReturn(UUID.randomUUID());
        when(log.getReasonCode()).thenReturn(reason);
        when(log.getActorType()).thenReturn(AuditActorType.SYSTEM);
        when(log.getActorId()).thenReturn(AuditLog.SYSTEM_ACTOR_ID);
        when(log.getTargetType()).thenReturn(AuditTargetType.FRAUD_CASE);
        when(log.getTargetId()).thenReturn(targetId);
        when(log.getCaseId()).thenReturn(caseId);
        when(log.getTransactionId()).thenReturn(transactionId);
        when(log.getBeforeValueSummary()).thenReturn(before);
        when(log.getAfterValueSummary()).thenReturn(after);
        when(log.getMetadata()).thenReturn(metadata);
        when(log.getChangedAt()).thenReturn(CHANGED_AT);
        when(log.getTraceId()).thenReturn("trace_stored_secret_01");
        return log;
    }

    private ObjectNode workflow(String status, UUID assignee) {
        ObjectNode node = object("caseStatus", status);
        if (assignee != null) {
            node.put("assigneeRef", assignee.toString());
        }
        return node;
    }

    private ObjectNode detectionMetadata() {
        return object("detectionResultId", UUID.randomUUID().toString())
                .put("detectionResultVersion", 1);
    }

    private ObjectNode object(String field, String value) {
        return objectMapper.createObjectNode().put(field, value);
    }
}
