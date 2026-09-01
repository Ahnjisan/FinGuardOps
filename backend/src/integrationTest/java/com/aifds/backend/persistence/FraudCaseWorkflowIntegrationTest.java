package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseWorkflowException;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudCaseWorkflowIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String FIRST_ASSIGNEE =
            "10000000-0000-4000-9000-000000000001";
    private static final String SECOND_ASSIGNEE =
            "20000000-0000-4000-9000-000000000002";
    private static final String TRACE_ID = "trace_case_workflow_it_01";

    @Autowired
    private FraudCaseWorkflowService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAllStatusTransitionsWithRealVersionAndOneAuditEach() {
        UUID caseId = insertOpenCase();

        FraudCaseMutationResponse started = service.changeStatus(
                statusCommand(
                        caseId,
                        FraudCaseStatus.IN_REVIEW,
                        true,
                        FIRST_ASSIGNEE,
                        AuditReasonCode.CASE_REVIEW_STARTED,
                        0L
                ),
                TRACE_ID
        );
        assertMutationAndAudit(
                started,
                "OPEN",
                null,
                "IN_REVIEW",
                FIRST_ASSIGNEE,
                "CASE_STATUS_CHANGED",
                "CASE_REVIEW_STARTED"
        );
        FraudCaseMutationResponse requested = service.changeStatus(
                statusCommand(
                        caseId,
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        false,
                        null,
                        AuditReasonCode.CASE_ADDITIONAL_INFORMATION_REQUESTED,
                        1L
                ),
                TRACE_ID
        );
        assertMutationAndAudit(
                requested,
                "IN_REVIEW",
                FIRST_ASSIGNEE,
                "ADDITIONAL_INFORMATION_REQUIRED",
                FIRST_ASSIGNEE,
                "CASE_STATUS_CHANGED",
                "CASE_ADDITIONAL_INFORMATION_REQUESTED"
        );
        FraudCaseMutationResponse resumed = service.changeStatus(
                statusCommand(
                        caseId,
                        FraudCaseStatus.IN_REVIEW,
                        false,
                        null,
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        2L
                ),
                TRACE_ID
        );
        assertMutationAndAudit(
                resumed,
                "ADDITIONAL_INFORMATION_REQUIRED",
                FIRST_ASSIGNEE,
                "IN_REVIEW",
                FIRST_ASSIGNEE,
                "CASE_STATUS_CHANGED",
                "CASE_REVIEW_RESUMED"
        );

        assertThat(started.concurrencyVersion()).isEqualTo(1L);
        assertThat(requested.concurrencyVersion()).isEqualTo(2L);
        assertThat(resumed.concurrencyVersion()).isEqualTo(3L);
        assertThat(started.reviewStartedAt()).isNotNull();
        assertThat(requested.reviewStartedAt())
                .isEqualTo(started.reviewStartedAt());
        assertThat(resumed.reviewStartedAt())
                .isEqualTo(started.reviewStartedAt());
        assertThat(resumed.finalDisposition()).isNull();
        assertThat(resumed.closedAt()).isNull();
        assertThat(caseRow(caseId)).containsEntry("case_status", "IN_REVIEW")
                .containsEntry("concurrency_version", 3L)
                .containsEntry("assignee_ref", FIRST_ASSIGNEE)
                .containsEntry(
                        "review_started_at",
                        Timestamp.from(started.reviewStartedAt())
                )
                .containsEntry(
                        "last_changed_at",
                        Timestamp.from(resumed.lastChangedAt())
                );
        assertThat(auditCount(caseId)).isEqualTo(3);
        assertThat(auditActions(caseId)).containsExactly(
                "CASE_STATUS_CHANGED:CASE_REVIEW_STARTED",
                "CASE_STATUS_CHANGED:CASE_ADDITIONAL_INFORMATION_REQUESTED",
                "CASE_STATUS_CHANGED:CASE_REVIEW_RESUMED"
        );
    }

    @Test
    void persistsApprovedAssigneeMatrixAndRejectsReviewRelease() {
        UUID caseId = insertOpenCase();
        service.changeStatus(statusCommand(
                caseId,
                FraudCaseStatus.IN_REVIEW,
                true,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_STARTED,
                0L
        ), TRACE_ID);
        FraudCaseMutationResponse changedInReview = service.changeAssignee(
                assigneeCommand(
                        caseId,
                        SECOND_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                        1L
                ),
                TRACE_ID
        );
        assertThat(changedInReview.assigneeRef()).isEqualTo(SECOND_ASSIGNEE);

        assertWorkflowReason(() -> service.changeAssignee(
                assigneeCommand(
                        caseId,
                        null,
                        AuditReasonCode.CASE_ASSIGNEE_RELEASED,
                        2L
                ),
                TRACE_ID
        ), FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT);

        service.changeStatus(statusCommand(
                caseId,
                FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                false,
                null,
                AuditReasonCode.CASE_ADDITIONAL_INFORMATION_REQUESTED,
                2L
        ), TRACE_ID);
        service.changeAssignee(assigneeCommand(
                caseId,
                null,
                AuditReasonCode.CASE_ASSIGNEE_RELEASED,
                3L
        ), TRACE_ID);
        service.changeAssignee(assigneeCommand(
                caseId,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_ASSIGNEE_ASSIGNED,
                4L
        ), TRACE_ID);
        FraudCaseMutationResponse changed = service.changeAssignee(
                assigneeCommand(
                        caseId,
                        SECOND_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                        5L
                ),
                TRACE_ID
        );

        assertThat(changed.concurrencyVersion()).isEqualTo(6L);
        assertThat(changed.assigneeRef()).isEqualTo(SECOND_ASSIGNEE);
        assertThat(auditCount(caseId)).isEqualTo(6);
    }

    @Test
    void prioritizesStaleVersionAndKeepsSameValueRequestAuditFree() {
        UUID caseId = insertOpenCase();
        service.changeStatus(statusCommand(
                caseId,
                FraudCaseStatus.IN_REVIEW,
                true,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_STARTED,
                0L
        ), TRACE_ID);

        assertWorkflowReason(() -> service.changeAssignee(
                assigneeCommand(
                        caseId,
                        FIRST_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                        0L
                ),
                TRACE_ID
        ), FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION);
        assertWorkflowReason(() -> service.changeAssignee(
                assigneeCommand(
                        caseId,
                        FIRST_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                        1L
                ),
                TRACE_ID
        ), FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT);

        assertThat(caseRow(caseId))
                .containsEntry("concurrency_version", 1L)
                .containsEntry("assignee_ref", FIRST_ASSIGNEE);
        assertThat(auditCount(caseId)).isEqualTo(1);
    }

    @Test
    void rollsBackCaseAndAuditWhenReasonOrAuditPersistenceFails() {
        UUID semanticCaseId = insertOpenCase();
        assertThatThrownBy(() -> service.changeStatus(statusCommand(
                semanticCaseId,
                FraudCaseStatus.IN_REVIEW,
                true,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_RESUMED,
                0L
        ), TRACE_ID)).isInstanceOf(FraudCaseValidationException.class);
        assertThat(caseRow(semanticCaseId))
                .containsEntry("case_status", "OPEN")
                .containsEntry("concurrency_version", 0L);
        assertThat(auditCount(semanticCaseId)).isZero();

        UUID assigneeReasonCaseId = insertOpenCase();
        service.changeStatus(statusCommand(
                assigneeReasonCaseId,
                FraudCaseStatus.IN_REVIEW,
                true,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_STARTED,
                0L
        ), TRACE_ID);
        assertThatThrownBy(() -> service.changeAssignee(assigneeCommand(
                assigneeReasonCaseId,
                SECOND_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_RESUMED,
                1L
        ), TRACE_ID)).isInstanceOf(FraudCaseValidationException.class);
        assertThat(caseRow(assigneeReasonCaseId))
                .containsEntry("case_status", "IN_REVIEW")
                .containsEntry("assignee_ref", FIRST_ASSIGNEE)
                .containsEntry("concurrency_version", 1L);
        assertThat(auditCount(assigneeReasonCaseId)).isEqualTo(1);

        UUID auditFailureCaseId = insertOpenCase();
        installAuditInsertRejectionTrigger();
        try {
            assertThatThrownBy(() -> service.changeStatus(statusCommand(
                    auditFailureCaseId,
                    FraudCaseStatus.IN_REVIEW,
                    true,
                    FIRST_ASSIGNEE,
                    AuditReasonCode.CASE_REVIEW_STARTED,
                    0L
            ), TRACE_ID)).isInstanceOf(DataAccessException.class);
        } finally {
            removeAuditInsertRejectionTrigger();
        }
        assertThat(caseRow(auditFailureCaseId))
                .containsEntry("case_status", "OPEN")
                .containsEntry("concurrency_version", 0L)
                .containsEntry("assignee_ref", null);
        assertThat(auditCount(auditFailureCaseId)).isZero();
    }

    private FraudCaseWorkflowCommand.StatusChange statusCommand(
            UUID caseId,
            FraudCaseStatus target,
            boolean assigneePresent,
            String assignee,
            AuditReasonCode reason,
            long expectedVersion
    ) {
        return new FraudCaseWorkflowCommand.StatusChange(
                caseId,
                target,
                assigneePresent,
                assignee,
                reason,
                expectedVersion
        );
    }

    private FraudCaseWorkflowCommand.AssigneeChange assigneeCommand(
            UUID caseId,
            String assignee,
            AuditReasonCode reason,
            long expectedVersion
    ) {
        return new FraudCaseWorkflowCommand.AssigneeChange(
                caseId,
                assignee,
                reason,
                expectedVersion
        );
    }

    private UUID insertOpenCase() {
        UUID caseId = UUID.randomUUID();
        Instant createdAt = Instant.now()
                .minusSeconds(1)
                .truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, 'OPEN', 0, ?, ?)
                        """,
                caseId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
        return caseId;
    }

    private Map<String, Object> caseRow(UUID caseId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT case_status, final_disposition, assignee_ref,
                               review_started_at, closed_at,
                               concurrency_version, last_changed_at
                        FROM fraud_case
                        WHERE case_id = ?
                        """,
                caseId
        );
    }

    private int auditCount(UUID caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE case_id = ?",
                Integer.class,
                caseId
        );
    }

    private java.util.List<String> auditActions(UUID caseId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT action || ':' || reason_code
                        FROM audit_log
                        WHERE case_id = ?
                        ORDER BY id
                        """,
                String.class,
                caseId
        );
    }

    private void assertMutationAndAudit(
            FraudCaseMutationResponse response,
            String beforeStatus,
            String beforeAssignee,
            String afterStatus,
            String afterAssignee,
            String action,
            String reasonCode
    ) {
        Map<String, Object> caseRow = caseRow(response.caseId());
        assertThat(caseRow)
                .containsEntry("case_status", response.caseStatus().name())
                .containsEntry("assignee_ref", response.assigneeRef())
                .containsEntry(
                        "concurrency_version",
                        response.concurrencyVersion()
                )
                .containsEntry(
                        "review_started_at",
                        Timestamp.from(response.reviewStartedAt())
                )
                .containsEntry(
                        "last_changed_at",
                        Timestamp.from(response.lastChangedAt())
                );

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                """
                        SELECT action, reason_code,
                               before_value_summary ->> 'caseStatus'
                                   AS before_status,
                               before_value_summary ->> 'assigneeRef'
                                   AS before_assignee,
                               after_value_summary ->> 'caseStatus'
                                   AS after_status,
                               after_value_summary ->> 'assigneeRef'
                                   AS after_assignee,
                               before_value_summary
                                   - 'caseStatus' - 'assigneeRef'
                                   = '{}'::jsonb AS before_schema_exact,
                               after_value_summary
                                   - 'caseStatus' - 'assigneeRef'
                                   = '{}'::jsonb AS after_schema_exact,
                               metadata = '{}'::jsonb AS metadata_empty
                        FROM audit_log
                        WHERE case_id = ?
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                response.caseId()
        );
        assertThat(audit)
                .containsEntry("action", action)
                .containsEntry("reason_code", reasonCode)
                .containsEntry("before_status", beforeStatus)
                .containsEntry("before_assignee", beforeAssignee)
                .containsEntry("after_status", afterStatus)
                .containsEntry("after_assignee", afterAssignee)
                .containsEntry("before_schema_exact", true)
                .containsEntry("after_schema_exact", true)
                .containsEntry("metadata_empty", true);
    }

    private void installAuditInsertRejectionTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_case_workflow_audit_insert()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced workflow audit failure'
                        USING ERRCODE = '55000';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_case_workflow_audit_insert
                    BEFORE INSERT ON audit_log
                    FOR EACH ROW
                    EXECUTE FUNCTION reject_case_workflow_audit_insert()
                """);
    }

    private void removeAuditInsertRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS
                    tr_reject_case_workflow_audit_insert ON audit_log
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS reject_case_workflow_audit_insert()"
        );
    }

    private void assertWorkflowReason(
            Runnable invocation,
            FraudCaseWorkflowException.Reason expected
    ) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(FraudCaseWorkflowException.class)
                .extracting(exception ->
                        ((FraudCaseWorkflowException) exception).getReason()
                ).isEqualTo(expected);
    }
}
