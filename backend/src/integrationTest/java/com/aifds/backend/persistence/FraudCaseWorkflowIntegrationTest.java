package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
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
@org.springframework.security.test.context.support.WithMockUser(
        authorities = {
                "case:workflow:write",
                "case:resolution:write"
        }
)
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

    @Test
    void resolvesEveryDispositionWithExactDatabaseAndAuditSnapshot() {
        for (FraudCaseFinalDisposition disposition
                : FraudCaseFinalDisposition.values()) {
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
            Timestamp createdAt = (Timestamp) caseRow(caseId).get("created_at");

            FraudCaseMutationResponse resolved = service.resolve(
                    resolutionCommand(caseId, disposition.name(), 1L),
                    TRACE_ID
            );

            assertThat(resolved.caseStatus())
                    .isEqualTo(FraudCaseStatus.CLOSED);
            assertThat(resolved.finalDisposition()).isEqualTo(disposition);
            assertThat(resolved.assigneeRef()).isEqualTo(FIRST_ASSIGNEE);
            assertThat(resolved.reviewStartedAt())
                    .isEqualTo(started.reviewStartedAt());
            assertThat(resolved.closedAt())
                    .isEqualTo(resolved.lastChangedAt());
            assertThat(resolved.concurrencyVersion()).isEqualTo(2L);
            assertThat(caseRow(caseId))
                    .containsEntry("case_status", "CLOSED")
                    .containsEntry("final_disposition", disposition.name())
                    .containsEntry("assignee_ref", FIRST_ASSIGNEE)
                    .containsEntry(
                            "review_started_at",
                            Timestamp.from(started.reviewStartedAt())
                    )
                    .containsEntry(
                            "closed_at",
                            Timestamp.from(resolved.closedAt())
                    )
                    .containsEntry(
                            "last_changed_at",
                            Timestamp.from(resolved.closedAt())
                    )
                    .containsEntry("concurrency_version", 2L)
                    .containsEntry("created_at", createdAt);

            Map<String, Object> audit = resolutionAudit(caseId);
            assertThat(audit)
                    .containsEntry("action", "CASE_RESOLVED")
                    .containsEntry(
                            "reason_code",
                            "CASE_RESOLUTION_COMPLETED"
                    )
                    .containsEntry("actor_type", "SYSTEM")
                    .containsEntry("actor_id", "finguardops-backend")
                    .containsEntry("target_id", caseId)
                    .containsEntry("case_id", caseId)
                    .containsEntry("transaction_id", null)
                    .containsEntry("before_exact", true)
                    .containsEntry("after_exact", true)
                    .containsEntry("metadata_empty", true);
            assertThat(auditCount(caseId)).isEqualTo(2);
        }
    }

    @Test
    void resolutionPrioritizesVersionClosedStateAndBusinessErrors() {
        UUID caseId = insertOpenCase();
        service.changeStatus(statusCommand(
                caseId,
                FraudCaseStatus.IN_REVIEW,
                true,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_STARTED,
                0L
        ), TRACE_ID);
        service.resolve(
                resolutionCommand(caseId, "NORMAL", 1L),
                TRACE_ID
        );

        assertWorkflowReason(() -> service.resolve(
                resolutionCommand(caseId, "NORMAL", 1L),
                TRACE_ID
        ), FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION);
        for (String disposition : new String[]{"NORMAL", "CONFIRMED_FRAUD"}) {
            assertWorkflowReason(() -> service.resolve(
                    resolutionCommand(caseId, disposition, 2L),
                    TRACE_ID
            ), FraudCaseWorkflowException.Reason.CASE_ALREADY_CLOSED);
        }

        UUID openCaseId = insertOpenCase();
        assertWorkflowReason(() -> service.resolve(
                resolutionCommand(openCaseId, "INVALID", 0L),
                TRACE_ID
        ), FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT);
        assertThat(caseRow(openCaseId))
                .containsEntry("case_status", "OPEN")
                .containsEntry("concurrency_version", 0L);
        assertThat(auditCount(openCaseId)).isZero();

        UUID semanticCaseId = insertOpenCase();
        service.changeStatus(statusCommand(
                semanticCaseId,
                FraudCaseStatus.IN_REVIEW,
                true,
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_REVIEW_STARTED,
                0L
        ), TRACE_ID);
        assertThatThrownBy(() -> service.resolve(
                resolutionCommand(semanticCaseId, "normal", 1L),
                TRACE_ID
        )).isInstanceOf(FraudCaseValidationException.class);
        assertThatThrownBy(() -> service.resolve(
                new FraudCaseWorkflowCommand.Resolution(
                        semanticCaseId,
                        "NORMAL",
                        "CASE_REVIEW_STARTED",
                        1L
                ),
                TRACE_ID
        )).isInstanceOf(FraudCaseValidationException.class);
        assertThat(caseRow(semanticCaseId))
                .containsEntry("case_status", "IN_REVIEW")
                .containsEntry("final_disposition", null)
                .containsEntry("concurrency_version", 1L);
        assertThat(auditCount(semanticCaseId)).isEqualTo(1);
    }

    @Test
    void resolutionAuditConstraintFailureRollsBackEveryCaseField() {
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
        jdbcTemplate.execute("""
                ALTER TABLE audit_log
                ADD CONSTRAINT ck_test_reject_resolution
                CHECK (action <> 'CASE_RESOLVED')
                """);
        try {
            assertThatThrownBy(() -> service.resolve(
                    resolutionCommand(caseId, "CONFIRMED_FRAUD", 1L),
                    TRACE_ID
            )).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE audit_log
                    DROP CONSTRAINT IF EXISTS ck_test_reject_resolution
                    """);
        }

        assertThat(caseRow(caseId))
                .containsEntry("case_status", "IN_REVIEW")
                .containsEntry("final_disposition", null)
                .containsEntry("assignee_ref", FIRST_ASSIGNEE)
                .containsEntry("closed_at", null)
                .containsEntry(
                        "review_started_at",
                        Timestamp.from(started.reviewStartedAt())
                )
                .containsEntry("concurrency_version", 1L)
                .containsEntry(
                        "last_changed_at",
                        Timestamp.from(started.lastChangedAt())
                );
        assertThat(auditCount(caseId)).isEqualTo(1);
    }

    @Test
    void abnormalInReviewWithoutReviewStartFailsWithoutDatabaseChanges() {
        UUID caseId = UUID.randomUUID();
        Instant createdAt = Instant.now()
                .minusSeconds(1)
                .truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, assignee_ref,
                            review_started_at, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, 'IN_REVIEW', ?, NULL, 0, ?, ?)
                        """,
                caseId,
                FIRST_ASSIGNEE,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        assertThatThrownBy(() -> service.resolve(
                resolutionCommand(caseId, "NORMAL", 0L),
                TRACE_ID
        )).isInstanceOf(FraudCaseWorkflowException.class)
                .extracting(exception ->
                        ((FraudCaseWorkflowException) exception).getReason()
                ).isEqualTo(
                        FraudCaseWorkflowException.Reason
                                .INCONSISTENT_CASE_DATA
                );
        assertThat(caseRow(caseId))
                .containsEntry("case_status", "IN_REVIEW")
                .containsEntry("final_disposition", null)
                .containsEntry("assignee_ref", FIRST_ASSIGNEE)
                .containsEntry("review_started_at", null)
                .containsEntry("closed_at", null)
                .containsEntry("concurrency_version", 0L)
                .containsEntry("created_at", Timestamp.from(createdAt))
                .containsEntry("last_changed_at", Timestamp.from(createdAt));
        assertThat(auditCount(caseId)).isZero();
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

    private FraudCaseWorkflowCommand.Resolution resolutionCommand(
            UUID caseId,
            String disposition,
            long expectedVersion
    ) {
        return new FraudCaseWorkflowCommand.Resolution(
                caseId,
                disposition,
                "CASE_RESOLUTION_COMPLETED",
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
                               concurrency_version, created_at, last_changed_at
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

    private Map<String, Object> resolutionAudit(UUID caseId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT action, reason_code, actor_type, actor_id,
                               target_id, transaction_id, case_id,
                               before_value_summary = jsonb_build_object(
                                   'caseStatus', 'IN_REVIEW',
                                   'assigneeRef', ?::text
                               ) AS before_exact,
                               after_value_summary = jsonb_build_object(
                                   'caseStatus', 'CLOSED',
                                   'finalDisposition',
                                       after_value_summary
                                           ->> 'finalDisposition',
                                   'assigneeRef', ?::text
                               ) AS after_exact,
                               metadata = '{}'::jsonb AS metadata_empty
                        FROM audit_log
                        WHERE case_id = ? AND action = 'CASE_RESOLVED'
                        """,
                FIRST_ASSIGNEE,
                FIRST_ASSIGNEE,
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
