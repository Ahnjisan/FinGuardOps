package com.aifds.backend.audit.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuditLogTest {

    private static final Instant CHANGED_AT =
            Instant.parse("2026-08-20T04:50:10.123456Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsImmutableSystemAuditLogAndDefensivelyCopiesJson() {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        ObjectNode after = objectMapper.createObjectNode()
                .put("caseStatus", "OPEN");
        ObjectNode metadata = objectMapper.createObjectNode()
                .put("detectionResultVersion", 1);

        AuditLog auditLog = AuditLog.create(
                UUID.randomUUID(),
                AuditActorType.SYSTEM,
                "finguardops-backend",
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "trace_audit_entity_01",
                null,
                after,
                metadata,
                CHANGED_AT
        );

        after.put("caseStatus", "CLOSED");
        metadata.put("detectionResultVersion", 2);
        ((ObjectNode) auditLog.getAfterValueSummary())
                .put("caseStatus", "CLOSED");
        ((ObjectNode) auditLog.getMetadata())
                .put("detectionResultVersion", 3);

        assertThat(auditLog.getAuditId().version()).isEqualTo(4);
        assertThat(AuditLog.SYSTEM_ACTOR_ID)
                .isEqualTo("finguardops-backend");
        assertThat(auditLog.getAfterValueSummary().get("caseStatus").textValue())
                .isEqualTo("OPEN");
        assertThat(auditLog.getMetadata().get("detectionResultVersion").intValue())
                .isEqualTo(1);
        assertThat(auditLog.getChangedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void acceptsUserActorOnlyWithCanonicalUuidV4() {
        String userId = UUID.randomUUID().toString();
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        AuditLog auditLog = createCaseAudit(
                AuditActorType.USER,
                userId,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "trace_audit_user_01",
                CHANGED_AT
        );

        assertThat(auditLog.getActorId()).isEqualTo(userId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "f47ac10b-58cc-11cf-a447-001122334455",
            "not-a-uuid",
            "john.smith",
            "analyst@example.com",
            "12345678",
            "010-1234-5678"
    })
    void rejectsNonUuidV4UserActorIds(String actorId) {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.USER,
                actorId,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "trace_audit_user_02",
                CHANGED_AT
        )).withMessageContaining("UUID v4");
    }

    @ParameterizedTest
    @MethodSource("approvedActionContracts")
    void acceptsEveryApprovedActionContract(ActionContract contract) {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        AuditLog auditLog = createActionAudit(
                contract,
                contract.reasonCode(),
                contract.targetType(),
                contract.caseTarget() ? caseId : transactionId,
                contract.transactionRequired() ? transactionId : null,
                contract.caseTarget() ? caseId : null
        );

        assertThat(auditLog.getAction().name())
                .isEqualTo(contract.expectedAction());
        assertThat(auditLog.getReasonCode().name())
                .isEqualTo(contract.expectedReasonCode());
        assertThat(auditLog.getTargetType().name())
                .isEqualTo(contract.expectedTargetType());
        assertThat(auditLog.getActorId()).isEqualTo("finguardops-backend");
    }

    @ParameterizedTest
    @MethodSource("approvedActionContracts")
    void rejectsWrongReasonForEveryAction(ActionContract contract) {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() ->
                createActionAudit(
                        contract,
                        wrongReason(contract),
                        contract.targetType(),
                        contract.caseTarget() ? caseId : transactionId,
                        contract.transactionRequired() ? transactionId : null,
                        contract.caseTarget() ? caseId : null
                )
        ).withMessageContaining("reasonCode");
    }

    @ParameterizedTest
    @MethodSource("approvedActionContracts")
    void rejectsWrongTargetTypeAndMismatchedTargetIdForEveryAction(
            ActionContract contract
    ) {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID targetId = contract.caseTarget() ? caseId : transactionId;
        UUID contextCaseId = contract.caseTarget() ? caseId : null;
        AuditTargetType wrongTargetType = contract.caseTarget()
                ? AuditTargetType.FINANCIAL_TRANSACTION
                : AuditTargetType.FRAUD_CASE;

        assertThatIllegalArgumentException().isThrownBy(() ->
                createActionAudit(
                        contract,
                        contract.reasonCode(),
                        wrongTargetType,
                        targetId,
                        contract.transactionRequired() ? transactionId : null,
                        contextCaseId
                )
        ).withMessageContaining("target and context");
        assertThatIllegalArgumentException().isThrownBy(() ->
                createActionAudit(
                        contract,
                        contract.reasonCode(),
                        contract.targetType(),
                        UUID.randomUUID(),
                        contract.transactionRequired() ? transactionId : null,
                        contextCaseId
                )
        ).withMessageContaining("target and context");
    }

    @ParameterizedTest
    @MethodSource("transactionRequiredActionContracts")
    void rejectsMissingTransactionContextForEveryAction(
            ActionContract contract
    ) {
        UUID caseId = UUID.randomUUID();
        UUID targetId = contract.caseTarget() ? caseId : UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() ->
                createActionAudit(
                        contract,
                        contract.reasonCode(),
                        contract.targetType(),
                        targetId,
                        null,
                        contract.caseTarget() ? caseId : null
                )
        ).withMessageContaining("target and context");
    }

    @ParameterizedTest
    @EnumSource(
            value = AuditAction.class,
            names = {
                    "CASE_STATUS_CHANGED",
                    "CASE_ASSIGNEE_CHANGED",
                    "CASE_RESOLVED"
            }
    )
    void rejectsTransactionContextForWorkflowActions(AuditAction action) {
        ActionContract contract = contract(action);
        UUID caseId = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() ->
                createActionAudit(
                        contract,
                        contract.reasonCode(),
                        contract.targetType(),
                        caseId,
                        UUID.randomUUID(),
                        caseId
                )
        ).withMessageContaining("target and context");
    }

    @ParameterizedTest
    @EnumSource(
            value = AuditAction.class,
            names = {"CASE_CREATED", "CASE_TRANSACTION_LINKED"}
    )
    void rejectsMissingCaseContextForCaseActions(AuditAction action) {
        ActionContract contract = contract(action);

        assertThatIllegalArgumentException().isThrownBy(() ->
                createActionAudit(
                        contract,
                        contract.reasonCode(),
                        contract.targetType(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null
                )
        ).withMessageContaining("target and context");
    }

    @Test
    void rejectsInvalidActorReasonTargetTraceAndTimestamp() {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.SYSTEM,
                "another-system",
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "trace_audit_entity_01",
                CHANGED_AT
        )).withMessageContaining("SYSTEM actorId");

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.USER,
                "contains space",
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "trace_audit_entity_01",
                CHANGED_AT
        )).withMessageContaining("UUID v4");

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "trace_audit_entity_01",
                CHANGED_AT
        )).withMessageContaining("reasonCode");

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transactionId,
                transactionId,
                caseId,
                "trace_audit_entity_01",
                CHANGED_AT
        )).withMessageContaining("target and context");

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                "short",
                CHANGED_AT
        )).withMessageContaining("8 to 64");

        assertThatIllegalArgumentException().isThrownBy(() -> createCaseAudit(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                null,
                Instant.parse("2026-08-20T04:50:10.123456789Z")
        )).withMessageContaining("microsecond precision");
    }

    @Test
    void exposesNoMutationMethodsAndDeclaresLifecycleGuards() {
        assertThat(AuditLog.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(Arrays.stream(AuditLog.class.getMethods())
                .filter(method -> method.getDeclaringClass() == AuditLog.class)
                .map(Method::getName))
                .noneMatch(name -> name.startsWith("set"))
                .noneMatch(name -> name.startsWith("delete"))
                .noneMatch(name -> name.startsWith("remove"));
        assertThat(Arrays.stream(AuditLog.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreUpdate.class)))
                .hasSize(1)
                .allMatch(method -> Modifier.isPrivate(method.getModifiers()));
        assertThat(Arrays.stream(AuditLog.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreRemove.class)))
                .hasSize(1)
                .allMatch(method -> Modifier.isPrivate(method.getModifiers()));
    }

    private AuditLog createCaseAudit(
            AuditActorType actorType,
            String actorId,
            AuditReasonCode reasonCode,
            AuditTargetType targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            String traceId,
            Instant changedAt
    ) {
        return AuditLog.create(
                UUID.randomUUID(),
                actorType,
                actorId,
                AuditAction.CASE_CREATED,
                reasonCode,
                targetType,
                targetId,
                transactionId,
                caseId,
                traceId,
                null,
                objectMapper.createObjectNode().put("caseStatus", "OPEN"),
                objectMapper.createObjectNode(),
                changedAt
        );
    }

    private AuditLog createActionAudit(
            ActionContract contract,
            AuditReasonCode reasonCode,
            AuditTargetType targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId
    ) {
        ObjectNode before = null;
        ObjectNode after;
        switch (contract.action()) {
            case CASE_CREATED -> after = objectMapper.createObjectNode()
                    .put("caseStatus", "OPEN");
            case CASE_TRANSACTION_LINKED -> after = objectMapper
                    .createObjectNode()
                    .put("linked", true);
            case CASE_STATUS_CHANGED -> {
                before = objectMapper.createObjectNode()
                        .put("caseStatus", "OPEN");
                after = objectMapper.createObjectNode()
                        .put("caseStatus", "IN_REVIEW")
                        .put(
                                "assigneeRef",
                                "10000000-0000-4000-9000-000000000001"
                        );
            }
            case CASE_ASSIGNEE_CHANGED -> {
                before = objectMapper.createObjectNode()
                        .put(
                                "caseStatus",
                                "ADDITIONAL_INFORMATION_REQUIRED"
                        );
                after = objectMapper.createObjectNode()
                        .put(
                                "caseStatus",
                                "ADDITIONAL_INFORMATION_REQUIRED"
                        )
                        .put(
                                "assigneeRef",
                                "10000000-0000-4000-9000-000000000001"
                        );
            }
            case CASE_RESOLVED -> {
                before = objectMapper.createObjectNode()
                        .put("caseStatus", "IN_REVIEW")
                        .put(
                                "assigneeRef",
                                "10000000-0000-4000-9000-000000000001"
                        );
                after = objectMapper.createObjectNode()
                        .put("caseStatus", "CLOSED")
                        .put("finalDisposition", "CONFIRMED_FRAUD")
                        .put(
                                "assigneeRef",
                                "10000000-0000-4000-9000-000000000001"
                        );
            }
            case TRANSACTION_RISK_RESPONSE_APPLIED -> after = objectMapper
                    .createObjectNode()
                    .put("riskResponseOutcome", "HELD");
            case TRANSACTION_STATUS_CHANGED -> {
                before = objectMapper.createObjectNode()
                        .put("processingStatus", "ANALYZED");
                after = objectMapper.createObjectNode()
                        .put("processingStatus", "HELD");
            }
            default -> throw new IllegalStateException("Unexpected action");
        }
        return AuditLog.create(
                UUID.randomUUID(),
                AuditActorType.SYSTEM,
                "finguardops-backend",
                contract.action(),
                reasonCode,
                targetType,
                targetId,
                transactionId,
                caseId,
                "trace_audit_matrix_01",
                before,
                after,
                objectMapper.createObjectNode(),
                CHANGED_AT
        );
    }

    private static AuditReasonCode wrongReason(ActionContract contract) {
        return contract.reasonCode()
                == AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY
                ? AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY
                : AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY;
    }

    private static Stream<ActionContract> approvedActionContracts() {
        return Stream.of(
                new ActionContract(
                        AuditAction.CASE_CREATED,
                        "CASE_CREATED",
                        AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                        "CASE_REQUIRED_BY_RISK_POLICY",
                        AuditTargetType.FRAUD_CASE,
                        "FRAUD_CASE",
                        true,
                        true
                ),
                new ActionContract(
                        AuditAction.CASE_TRANSACTION_LINKED,
                        "CASE_TRANSACTION_LINKED",
                        AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                        "CASE_REQUIRED_BY_RISK_POLICY",
                        AuditTargetType.FRAUD_CASE,
                        "FRAUD_CASE",
                        true,
                        true
                ),
                new ActionContract(
                        AuditAction.CASE_STATUS_CHANGED,
                        "CASE_STATUS_CHANGED",
                        AuditReasonCode.CASE_REVIEW_STARTED,
                        "CASE_REVIEW_STARTED",
                        AuditTargetType.FRAUD_CASE,
                        "FRAUD_CASE",
                        true,
                        false
                ),
                new ActionContract(
                        AuditAction.CASE_ASSIGNEE_CHANGED,
                        "CASE_ASSIGNEE_CHANGED",
                        AuditReasonCode.CASE_ASSIGNEE_ASSIGNED,
                        "CASE_ASSIGNEE_ASSIGNED",
                        AuditTargetType.FRAUD_CASE,
                        "FRAUD_CASE",
                        true,
                        false
                ),
                new ActionContract(
                        AuditAction.CASE_RESOLVED,
                        "CASE_RESOLVED",
                        AuditReasonCode.CASE_RESOLUTION_COMPLETED,
                        "CASE_RESOLUTION_COMPLETED",
                        AuditTargetType.FRAUD_CASE,
                        "FRAUD_CASE",
                        true,
                        false
                ),
                new ActionContract(
                        AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                        "TRANSACTION_RISK_RESPONSE_APPLIED",
                        AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                        "RISK_RESPONSE_DECIDED_BY_POLICY",
                        AuditTargetType.FINANCIAL_TRANSACTION,
                        "FINANCIAL_TRANSACTION",
                        false,
                        true
                ),
                new ActionContract(
                        AuditAction.TRANSACTION_STATUS_CHANGED,
                        "TRANSACTION_STATUS_CHANGED",
                        AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                        "TRANSACTION_FINALIZED_BY_RISK_POLICY",
                        AuditTargetType.FINANCIAL_TRANSACTION,
                        "FINANCIAL_TRANSACTION",
                        false,
                        true
                )
        );
    }

    private static Stream<ActionContract> transactionRequiredActionContracts() {
        return approvedActionContracts().filter(
                ActionContract::transactionRequired
        );
    }

    private static ActionContract contract(AuditAction action) {
        return approvedActionContracts()
                .filter(candidate -> candidate.action() == action)
                .findFirst()
                .orElseThrow();
    }

    private record ActionContract(
            AuditAction action,
            String expectedAction,
            AuditReasonCode reasonCode,
            String expectedReasonCode,
            AuditTargetType targetType,
            String expectedTargetType,
            boolean caseTarget,
            boolean transactionRequired
    ) {
    }
}
