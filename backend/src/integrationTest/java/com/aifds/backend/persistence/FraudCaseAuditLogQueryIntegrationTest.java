package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FraudCaseAuditLogQueryIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final UUID CASE_A = UUID.fromString(
            "10000000-0000-4000-9000-000000000001"
    );
    private static final UUID CASE_B = UUID.fromString(
            "10000000-0000-4000-9000-000000000002"
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
    private static final UUID NOTE_A = UUID.fromString(
            "4a000000-0000-4000-9000-000000000001"
    );
    private static final UUID NOTE_B = UUID.fromString(
            "4b000000-0000-4000-9000-000000000001"
    );
    private static final UUID NOTE_C = UUID.fromString(
            "4c000000-0000-4000-9000-000000000001"
    );
    private static final Instant BASE =
            Instant.parse("2026-09-01T00:00:00Z");
    private static final String PATH =
            "/api/v1/cases/" + CASE_A + "/audit-logs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AuditLogPersistenceService auditLogPersistenceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedCasesAndAudits() {
        insertTransaction();
        insertTransaction(CASE_A);
        insertCase(CASE_A);
        insertCase(CASE_B);
        insertApprovedCaseActions();
        insertAudit(
                UUID.fromString("50000000-0000-4000-9000-000000000001"),
                "CASE_CREATED", "CASE_REQUIRED_BY_RISK_POLICY",
                "FRAUD_CASE", CASE_B, TRANSACTION_ID, CASE_B,
                null, object("caseStatus", "OPEN"), empty(),
                BASE.plusSeconds(10)
        );
        insertAudit(
                UUID.fromString("50000000-0000-4000-9000-000000000002"),
                "TRANSACTION_STATUS_CHANGED",
                "TRANSACTION_FINALIZED_BY_RISK_POLICY",
                "FINANCIAL_TRANSACTION", TRANSACTION_ID,
                TRANSACTION_ID, null,
                object("processingStatus", "ANALYZED"),
                object("processingStatus", "HELD"),
                object("sourceRiskLevel", "HIGH"),
                BASE.plusSeconds(11)
        );
        auditLogPersistenceService.append(new AuditLogDraft(
                AuditActorType.SYSTEM,
                "finguardops-backend",
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                CASE_A,
                CASE_A,
                null,
                "trace_same_target_different_type_01",
                object("processingStatus", "ANALYZED"),
                object("processingStatus", "HELD"),
                object("sourceRiskLevel", "HIGH")
        ));
    }

    @Test
    void returnsAllSixTypedProjectionsAndExcludesOtherTargets() throws Exception {
        String traceId = "trace_audit_integration_01";
        String json = mockMvc.perform(get(PATH)
                        .queryParam("size", "20")
                        .queryParam("sort", "changedAt,asc")
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(CASE_A.toString()))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.content.length()").value(8))
                .andExpect(jsonPath("$.content[0].action")
                        .value("CASE_CREATED"))
                .andExpect(jsonPath("$.content[0].beforeSummary")
                        .value(nullValue()))
                .andExpect(jsonPath("$.content[0].metadata").isEmpty())
                .andExpect(jsonPath("$.content[1].action")
                        .value("CASE_TRANSACTION_LINKED"))
                .andExpect(jsonPath("$.content[1].afterSummary.linked")
                        .value(true))
                .andExpect(jsonPath("$.content[2].action")
                        .value("CASE_STATUS_CHANGED"))
                .andExpect(jsonPath(
                        "$.content[2].beforeSummary.assigneeRef"
                ).value(nullValue()))
                .andExpect(jsonPath("$.content[3].action")
                        .value("CASE_ASSIGNEE_CHANGED"))
                .andExpect(jsonPath("$.content[4].action")
                        .value("CASE_RESOLVED"))
                .andExpect(jsonPath(
                        "$.content[4].afterSummary.finalDisposition"
                ).value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.content[5].action")
                        .value("CASE_NOTE_CREATED"))
                .andExpect(jsonPath("$.content[5].metadata.noteId")
                        .value(NOTE_C.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(8))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContain(
                "FINANCIAL_TRANSACTION", CASE_B.toString(),
                "auditId", "actorId", "targetType", "targetId",
                "transactionId", "detectionResultId", "stored_trace",
                "content-preview", "provider-payload"
        );
    }

    @Test
    void paginatesAscAndDescWithoutDuplicatesOrGapsAndUsesIdTieBreaker()
            throws Exception {
        List<String> asc = signatures("changedAt,asc");
        List<String> desc = signatures("changedAt,desc");

        assertThat(asc).hasSize(8).doesNotHaveDuplicates();
        assertThat(desc).hasSize(8).doesNotHaveDuplicates();
        List<String> reversed = new ArrayList<>(asc);
        java.util.Collections.reverse(reversed);
        assertThat(desc).containsExactlyElementsOf(reversed);
        assertThat(asc.subList(6, 8)).containsExactly(
                "CASE_NOTE_CREATED:" + NOTE_A,
                "CASE_NOTE_CREATED:" + NOTE_B
        );
        assertThat(desc.subList(0, 2)).containsExactly(
                "CASE_NOTE_CREATED:" + NOTE_B,
                "CASE_NOTE_CREATED:" + NOTE_A
        );
    }

    @Test
    void returnsEmptyOutOfRangePageAndDistinguishesMissingCase()
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("page", "99")
                        .queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(8))
                .andExpect(jsonPath("$.page.totalPages").value(4));

        mockMvc.perform(get(PATH)
                        .queryParam("page", "2147483647")
                        .queryParam("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(8));

        mockMvc.perform(get("/api/v1/cases/"
                        + "10000000-0000-4000-9000-000000000099"
                        + "/audit-logs"))
                .andExpect(status().isNotFound());

        insertCase(UUID.fromString(
                "10000000-0000-4000-9000-000000000003"
        ));
        mockMvc.perform(get("/api/v1/cases/"
                        + "10000000-0000-4000-9000-000000000003"
                        + "/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void executesExactlyExistencePageAndCountQueriesWithoutNPlusOne()
            throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(
                SessionFactory.class
        ).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(get(PATH).queryParam("size", "20"))
                    .andExpect(status().isOk());
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    @Test
    void keepsFreshV1ThroughV13ExactIndexAndAppendOnlyRollbackContracts() {
        assertThat(flyway.info().applied()).hasSize(13);
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("13");
        String indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'audit_log'
                  AND indexname = 'ix_audit_log_target_changed'
                """, String.class);
        assertThat(indexDefinition).contains(
                "(target_type, target_id, changed_at DESC, id DESC)"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_log SET trace_id = 'changed' WHERE case_id = ?",
                CASE_A
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM audit_log WHERE case_id = ?", CASE_A
        )).isInstanceOf(DataAccessException.class);

        UUID rollbackAuditId = UUID.fromString(
                "60000000-0000-4000-9000-000000000001"
        );
        TransactionTemplate transaction = new TransactionTemplate(
                transactionManager
        );
        transaction.executeWithoutResult(status -> {
            auditLogPersistenceService.append(new AuditLogDraft(
                    AuditActorType.SYSTEM,
                    "finguardops-backend",
                    AuditAction.CASE_NOTE_CREATED,
                    AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED,
                    AuditTargetType.FRAUD_CASE,
                    CASE_A,
                    null,
                    CASE_A,
                    "trace_writer_rollback_01",
                    null,
                    null,
                    object("noteId", rollbackAuditId.toString())
            ));
            status.setRollbackOnly();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE metadata ->> 'noteId' = ?",
                Integer.class,
                rollbackAuditId.toString()
        )).isZero();
    }

    @Test
    void recordsActualAscDescAndCountExplainAnalyzeBuffersPlans() {
        insertRepresentativeRows();
        String desc = explain("DESC", "DESC");
        String asc = explain("ASC", "ASC");
        String count = String.join("\n", jdbcTemplate.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT COUNT(*)
                FROM audit_log
                WHERE target_type = 'FRAUD_CASE' AND target_id = ?
                """, String.class, CASE_A));

        assertThat(desc).contains("actual time", "Buffers:");
        assertThat(asc).contains("actual time", "Buffers:");
        assertThat(count).contains("Aggregate", "actual time", "Buffers:");
        System.out.println("AUDIT_QUERY_PLAN_DESC\n" + desc);
        System.out.println("AUDIT_QUERY_PLAN_ASC\n" + asc);
        System.out.println("AUDIT_QUERY_PLAN_COUNT\n" + count);
    }

    private List<String> signatures(String sort) throws Exception {
        List<String> signatures = new ArrayList<>();
        for (int page = 0; page < 8; page++) {
            String json = mockMvc.perform(get(PATH)
                            .queryParam("page", Integer.toString(page))
                            .queryParam("size", "1")
                            .queryParam("sort", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.page.totalElements").value(8))
                    .andExpect(jsonPath("$.page.totalPages").value(8))
                    .andReturn().getResponse().getContentAsString();
            JsonNode item = objectMapper.readTree(json).get("content").get(0);
            String signature = item.get("action").textValue();
            if (item.get("metadata").has("noteId")) {
                signature += ":" + item.get("metadata").get("noteId").textValue();
            } else {
                signature += ":" + item.get("changedAt").textValue();
            }
            signatures.add(signature);
        }
        return signatures;
    }

    private void insertApprovedCaseActions() {
        ObjectNode detection = object(
                "detectionResultId",
                "70000000-0000-4000-9000-000000000001"
        ).put("detectionResultVersion", 1);
        insertAudit(uuid(1), "CASE_CREATED", "CASE_REQUIRED_BY_RISK_POLICY",
                "FRAUD_CASE", CASE_A, TRANSACTION_ID, CASE_A, null,
                object("caseStatus", "OPEN"), detection, BASE);
        insertAudit(uuid(2), "CASE_TRANSACTION_LINKED",
                "CASE_REQUIRED_BY_RISK_POLICY", "FRAUD_CASE", CASE_A,
                TRANSACTION_ID, CASE_A, null,
                empty().put("linked", true), detection, BASE.plusSeconds(1));
        insertAudit(uuid(3), "CASE_STATUS_CHANGED", "CASE_REVIEW_STARTED",
                "FRAUD_CASE", CASE_A, null, CASE_A,
                object("caseStatus", "OPEN"),
                workflow("IN_REVIEW", ASSIGNEE_A), empty(),
                BASE.plusSeconds(2));
        insertAudit(uuid(4), "CASE_ASSIGNEE_CHANGED", "CASE_ASSIGNEE_CHANGED",
                "FRAUD_CASE", CASE_A, null, CASE_A,
                workflow("IN_REVIEW", ASSIGNEE_A),
                workflow("IN_REVIEW", ASSIGNEE_B), empty(),
                BASE.plusSeconds(3));
        insertAudit(uuid(5), "CASE_RESOLVED", "CASE_RESOLUTION_COMPLETED",
                "FRAUD_CASE", CASE_A, null, CASE_A,
                workflow("IN_REVIEW", ASSIGNEE_B),
                workflow("CLOSED", ASSIGNEE_B)
                        .put("finalDisposition", "CONFIRMED_FRAUD"),
                empty(), BASE.plusSeconds(4));
        insertAudit(uuid(6), "CASE_NOTE_CREATED",
                "CASE_INVESTIGATION_NOTE_ADDED", "FRAUD_CASE", CASE_A,
                null, CASE_A, null, null,
                object("noteId", NOTE_C.toString()), BASE.plusSeconds(5));
        insertAudit(uuid(7), "CASE_NOTE_CREATED",
                "CASE_INVESTIGATION_NOTE_ADDED", "FRAUD_CASE", CASE_A,
                null, CASE_A, null, null,
                object("noteId", NOTE_A.toString()), BASE.plusSeconds(6));
        insertAudit(uuid(8), "CASE_NOTE_CREATED",
                "CASE_INVESTIGATION_NOTE_ADDED", "FRAUD_CASE", CASE_A,
                null, CASE_A, null, null,
                object("noteId", NOTE_B.toString()), BASE.plusSeconds(6));
    }

    private void insertRepresentativeRows() {
        jdbcTemplate.update("""
                INSERT INTO audit_log (
                    audit_id, actor_type, actor_id, action, reason_code,
                    target_type, target_id, transaction_id, case_id, trace_id,
                    before_value_summary, after_value_summary, metadata,
                    changed_at
                )
                SELECT
                    ('a0000000-0000-4000-9000-'
                        || lpad(series::text, 12, '0'))::uuid,
                    'SYSTEM', 'finguardops-backend', 'CASE_NOTE_CREATED',
                    'CASE_INVESTIGATION_NOTE_ADDED', 'FRAUD_CASE', ?, NULL, ?,
                    'trace_representative_01', NULL, NULL,
                    jsonb_build_object('noteId',
                        'b0000000-0000-4000-9000-'
                        || lpad(series::text, 12, '0')),
                    ?::timestamptz + series * interval '1 microsecond'
                FROM generate_series(1, 2000) AS series
                """, CASE_B, CASE_B, Timestamp.from(BASE.plusSeconds(20)));
    }

    private String explain(String changedDirection, String idDirection) {
        String sql = "EXPLAIN (ANALYZE, BUFFERS) "
                + "SELECT * FROM audit_log "
                + "WHERE target_type = 'FRAUD_CASE' AND target_id = ? "
                + "ORDER BY changed_at " + changedDirection
                + ", id " + idDirection + " LIMIT 20 OFFSET 0";
        return String.join("\n", jdbcTemplate.queryForList(
                sql, String.class, CASE_A
        ));
    }

    private void insertCase(UUID caseId) {
        jdbcTemplate.update("""
                INSERT INTO fraud_case (
                    case_id, case_status, concurrency_version,
                    created_at, last_changed_at
                ) VALUES (?, 'OPEN', 0, ?, ?)
                """, caseId, Timestamp.from(BASE), Timestamp.from(BASE));
    }

    private void insertTransaction() {
        insertTransaction(TRANSACTION_ID);
    }

    private void insertTransaction(UUID transactionId) {
        jdbcTemplate.update("""
                INSERT INTO financial_transaction (
                    transaction_id, transaction_type, amount, currency_code,
                    occurred_at, external_customer_ref, sender_account_ref,
                    recipient_account_ref, channel, device_ref,
                    processing_status, created_at, updated_at
                ) VALUES (?, 'ACCOUNT_TRANSFER', ?, 'KRW', ?,
                    'customer_ref', 'sender_ref', 'recipient_ref',
                    'MOBILE_BANKING', 'device_ref', 'HELD', ?, ?)
                """,
                transactionId,
                new BigDecimal("10000"),
                Timestamp.from(BASE),
                Timestamp.from(BASE),
                Timestamp.from(BASE)
        );
    }

    private void insertAudit(
            UUID auditId,
            String action,
            String reason,
            String targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            JsonNode before,
            JsonNode after,
            JsonNode metadata,
            Instant changedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO audit_log (
                    audit_id, actor_type, actor_id, action, reason_code,
                    target_type, target_id, transaction_id, case_id, trace_id,
                    before_value_summary, after_value_summary, metadata,
                    changed_at
                ) VALUES (?, 'SYSTEM', 'finguardops-backend', ?, ?, ?, ?, ?, ?,
                    'trace_stored_credential_secret', ?::jsonb, ?::jsonb,
                    ?::jsonb, ?)
                """,
                auditId, action, reason, targetType, targetId, transactionId,
                caseId, json(before), json(after), json(metadata),
                Timestamp.from(changedAt)
        );
    }

    private UUID uuid(int suffix) {
        return UUID.fromString(String.format(
                "80000000-0000-4000-9000-%012d", suffix
        ));
    }

    private ObjectNode workflow(String status, UUID assignee) {
        ObjectNode node = object("caseStatus", status);
        if (assignee != null) {
            node.put("assigneeRef", assignee.toString());
        }
        return node;
    }

    private ObjectNode object(String field, String value) {
        return empty().put(field, value);
    }

    private ObjectNode empty() {
        return objectMapper.createObjectNode();
    }

    private String json(JsonNode value) {
        return value == null ? null : value.toString();
    }
}
