package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.audit.service.PersistedAuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditLogPersistenceIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final Instant BUSINESS_TIME =
            Instant.parse("2026-08-20T04:00:00Z");

    @Autowired
    private AuditLogPersistenceService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Flyway flyway;

    @Test
    void appliesFreshV1ThroughV8WithAuditSchemaAndAppendOnlyTrigger() {
        assertThat(flyway.info().applied()).hasSize(8);
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("8");
        assertThat(columns("audit_log")).containsExactlyInAnyOrder(
                "id",
                "audit_id",
                "actor_type",
                "actor_id",
                "action",
                "reason_code",
                "target_type",
                "target_id",
                "transaction_id",
                "case_id",
                "trace_id",
                "before_value_summary",
                "after_value_summary",
                "metadata",
                "changed_at"
        );
        assertThat(constraints("audit_log")).contains(
                "pk_audit_log",
                "uq_audit_log_audit_id",
                "ck_audit_log_actor",
                "ck_audit_log_action_reason",
                "ck_audit_log_target_context",
                "ck_audit_log_json_size",
                "ck_audit_log_summary_contract",
                "ck_audit_log_metadata_keys",
                "ck_audit_log_metadata_values",
                "fk_audit_log_transaction",
                "fk_audit_log_case"
        );
        assertThat(indexes("audit_log")).contains(
                "uq_audit_log_audit_id",
                "ix_audit_log_case_changed",
                "ix_audit_log_transaction_changed",
                "ix_audit_log_target_changed",
                "ix_audit_log_trace_changed"
        );
        assertThat(triggers("audit_log"))
                .contains("tr_audit_log_reject_mutation");
    }

    @Test
    void confirmsPostgresqlJsonbTextUtf8BoundaryAndWhitespace() {
        Map<String, Object> constraint = jdbcTemplate.queryForMap("""
                SELECT
                    conname AS constraint_name,
                    pg_get_constraintdef(pg_constraint.oid, true)
                        AS constraint_definition
                FROM pg_constraint
                WHERE conname = 'ck_audit_log_json_size'
                  AND conrelid = 'public.audit_log'::regclass
                """);
        String normalizedDefinition = constraint
                .get("constraint_definition")
                .toString()
                .replaceAll("[\\s()]", "")
                .toLowerCase(Locale.ROOT);

        assertThat(constraint.get("constraint_name"))
                .isEqualTo("ck_audit_log_json_size");
        assertThat(normalizedDefinition).contains(
                "coalesceoctet_lengthbefore_value_summary::text,0"
                        + "+coalesceoctet_length"
                        + "after_value_summary::text,0"
                        + "+octet_lengthmetadata::text<=8192"
        );

        Map<String, Object> sizes = jdbcTemplate.queryForMap("""
                WITH payloads AS (
                    SELECT
                        jsonb_build_object('v', repeat('x', 1000))
                            AS before_value_summary,
                        jsonb_build_object('v', repeat('x', 2000))
                            AS after_value_summary,
                        jsonb_build_object('v', repeat('x', 5165))
                            AS metadata_at_boundary,
                        jsonb_build_object('v', repeat('x', 5166))
                            AS metadata_over_boundary
                ), calculated AS (
                    SELECT
                        octet_length('{}'::jsonb::text) AS empty_bytes,
                        octet_length('{"a":"B"}'::jsonb::text)
                            AS one_entry_bytes,
                        octet_length(
                            '{"a":"B","linked":true}'::jsonb::text
                        ) AS multiple_entry_bytes,
                        COALESCE(
                            octet_length((NULL::jsonb)::text),
                            0
                        ) AS nullable_bytes,
                        COALESCE(
                            octet_length(before_value_summary::text),
                            0
                        )
                        + COALESCE(
                            octet_length(after_value_summary::text),
                            0
                        )
                        + octet_length(metadata_at_boundary::text)
                            AS boundary_bytes,
                        COALESCE(
                            octet_length(before_value_summary::text),
                            0
                        )
                        + COALESCE(
                            octet_length(after_value_summary::text),
                            0
                        )
                        + octet_length(metadata_over_boundary::text)
                            AS oversized_bytes
                    FROM payloads
                )
                SELECT
                    *,
                    boundary_bytes <= 8192 AS boundary_allowed,
                    oversized_bytes <= 8192 AS oversized_allowed
                FROM calculated
                """);

        assertThat(sizes.get("empty_bytes")).isEqualTo(2);
        assertThat(sizes.get("one_entry_bytes")).isEqualTo(10);
        assertThat(sizes.get("multiple_entry_bytes")).isEqualTo(26);
        assertThat(sizes.get("nullable_bytes")).isEqualTo(0);
        assertThat(sizes.get("boundary_bytes")).isEqualTo(8192);
        assertThat(sizes.get("oversized_bytes")).isEqualTo(8193);
        assertThat(sizes.get("boundary_allowed")).isEqualTo(true);
        assertThat(sizes.get("oversized_allowed")).isEqualTo(false);
    }

    @Test
    void acceptsUserUuidV4AndSystemLiteralActorContracts() {
        UUID transactionId = insertTransaction();
        UUID caseId = insertCase();
        RawAuditContract contract = rawContract(
                "CASE_CREATED",
                transactionId,
                caseId
        );

        insertRawAudit(
                UUID.randomUUID(),
                "USER",
                UUID.randomUUID().toString(),
                contract,
                "trace_audit_user_db_01"
        );
        insertRawAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                contract,
                "trace_audit_system_db_01"
        );

        assertThat(auditCount(transactionId)).isEqualTo(2);
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
        UUID transactionId = insertTransaction();
        UUID caseId = insertCase();
        RawAuditContract contract = rawContract(
                "CASE_CREATED",
                transactionId,
                caseId
        );

        assertThatThrownBy(() -> insertRawAudit(
                UUID.randomUUID(),
                "USER",
                actorId,
                contract,
                "trace_audit_user_db_02"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CASE_CREATED",
            "CASE_TRANSACTION_LINKED",
            "TRANSACTION_RISK_RESPONSE_APPLIED",
            "TRANSACTION_STATUS_CHANGED"
    })
    void insertsEveryApprovedActionThroughV7Checks(String action) {
        UUID transactionId = insertTransaction();
        UUID caseId = action.startsWith("CASE_") ? insertCase() : null;
        RawAuditContract contract = rawContract(
                action,
                transactionId,
                caseId
        );

        insertRawAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                contract,
                "trace_audit_matrix_ok_01"
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM audit_log WHERE transaction_id = ?",
                String.class,
                transactionId
        )).isEqualTo(action);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CASE_CREATED",
            "CASE_TRANSACTION_LINKED",
            "TRANSACTION_RISK_RESPONSE_APPLIED",
            "TRANSACTION_STATUS_CHANGED"
    })
    void rejectsInvalidV7ContractMatrixForEveryAction(String action) {
        UUID transactionId = insertTransaction();
        boolean caseAction = action.startsWith("CASE_");
        UUID caseId = caseAction ? insertCase() : null;
        RawAuditContract valid = rawContract(action, transactionId, caseId);
        String wrongReason = caseAction
                ? "RISK_RESPONSE_DECIDED_BY_POLICY"
                : "CASE_REQUIRED_BY_RISK_POLICY";
        String wrongTargetType = caseAction
                ? "FINANCIAL_TRANSACTION"
                : "FRAUD_CASE";

        assertRejected(valid.withReasonCode(wrongReason));
        assertRejected(valid.withTargetType(wrongTargetType));
        assertRejected(valid.withTargetId(UUID.randomUUID()));
        assertRejected(valid.withTransactionId(null));
        if (caseAction) {
            assertRejected(valid.withCaseId(null));
        }
        assertRejected(valid.withMetadata(object("customerId", "forbidden")));
        assertRejected(valid.withAfter(invalidAfter(action)));
        assertRejected(valid.withAfter(null));

        ObjectNode nested = emptyObject();
        nested.replace(summaryField(action), emptyObject());
        assertRejected(valid.withAfter(nested));
        assertRejected(valid.withAfter(objectMapper.createArrayNode()
                .add("invalid")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CASE_CREATED",
            "CASE_TRANSACTION_LINKED",
            "TRANSACTION_RISK_RESPONSE_APPLIED",
            "TRANSACTION_STATUS_CHANGED"
    })
    void rejectsJsonNullScalarThroughV7ChecksForEveryAction(String action) {
        UUID transactionId = insertTransaction();
        UUID caseId = action.startsWith("CASE_") ? insertCase() : null;
        RawAuditContract valid = rawContract(action, transactionId, caseId);
        ObjectNode afterWithNullScalar = emptyObject();
        afterWithNullScalar.putNull(summaryField(action));

        assertRejected(valid.withAfter(afterWithNullScalar));
    }

    @Test
    void insertsApprovedSystemAuditLogWithDefensiveJsonContract() {
        UUID transactionId = insertTransaction();

        PersistedAuditLog result = service.append(
                transactionStatusDraft(transactionId)
        );

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                        SELECT audit_id, actor_type, actor_id, action,
                               reason_code, target_type, target_id,
                               transaction_id, case_id, trace_id,
                               before_value_summary::text AS before_json,
                               after_value_summary::text AS after_json,
                               metadata::text AS metadata_json,
                               changed_at
                        FROM audit_log
                        WHERE audit_id = ?
                        """,
                result.auditId()
        );

        assertThat(result.auditId().version()).isEqualTo(4);
        assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(row.get("actor_id"))
                .isEqualTo("finguardops-backend");
        assertThat(row.get("action"))
                .isEqualTo("TRANSACTION_STATUS_CHANGED");
        assertThat(row.get("reason_code"))
                .isEqualTo("TRANSACTION_FINALIZED_BY_RISK_POLICY");
        assertThat(row.get("target_id")).isEqualTo(transactionId);
        assertThat(row.get("case_id")).isNull();
        assertThat(row.get("before_json").toString())
                .contains("ANALYZED");
        assertThat(row.get("after_json").toString()).contains("HELD");
        assertThat(row.get("metadata_json").toString())
                .contains("CRITICAL");
        assertThat(((Timestamp) row.get("changed_at")).toInstant().getNano()
                % 1_000).isZero();
    }

    @Test
    void rejectsUpdateAndDeleteThroughPostgresqlTrigger() {
        UUID transactionId = insertTransaction();
        UUID auditId = service.append(
                transactionStatusDraft(transactionId)
        ).auditId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_log SET trace_id = ? WHERE audit_id = ?",
                "trace_audit_changed_01",
                auditId
        )).isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("audit_log is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM audit_log WHERE audit_id = ?",
                auditId
        )).isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("audit_log is append-only");
        assertThat(auditCount(transactionId)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidChecksUniqueAndForeignKeys() {
        UUID transactionId = insertTransaction();
        UUID caseId = insertCase();
        UUID auditId = UUID.randomUUID();

        insertRawCaseAudit(
                auditId,
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_01",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        );

        assertThatThrownBy(() -> insertRawCaseAudit(
                auditId,
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_02",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "wrong-system",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_03",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "RISK_RESPONSE_DECIDED_BY_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_04",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                transactionId,
                transactionId,
                caseId,
                "trace_audit_db_05",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.nameUUIDFromBytes(
                        "not-v4".getBytes(StandardCharsets.UTF_8)
                ),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_05",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "UNKNOWN_ACTION",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_06",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "short",
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_07",
                null,
                object("caseStatus", "CLOSED"),
                emptyObject()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                "trace_audit_db_08",
                null,
                object("caseStatus", "OPEN"),
                object("customerId", "forbidden")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeysPreventDeletingReferencedBusinessRows() {
        UUID transactionId = insertTransaction();
        UUID caseId = insertCase();
        insertRawCaseAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                "CASE_CREATED",
                "CASE_REQUIRED_BY_RISK_POLICY",
                caseId,
                transactionId,
                caseId,
                null,
                null,
                object("caseStatus", "OPEN"),
                emptyObject()
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM fraud_case WHERE case_id = ?",
                caseId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM financial_transaction WHERE transaction_id = ?",
                transactionId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commitsBusinessDataAndAuditTogether() {
        UUID transactionId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    insertTransaction(transactionId);
                    service.append(transactionStatusDraft(transactionId));
                }
        );

        assertThat(transactionCount(transactionId)).isEqualTo(1);
        assertThat(auditCount(transactionId)).isEqualTo(1);
    }

    @Test
    void rollsBackBusinessDataWhenAuditInsertFails() {
        UUID transactionId = UUID.randomUUID();
        installInsertRejectionTrigger();
        try {
            assertThatThrownBy(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                insertTransaction(transactionId);
                                service.append(
                                        transactionStatusDraft(transactionId)
                                );
                            })
            ).isInstanceOf(DataAccessException.class);
        } finally {
            removeInsertRejectionTrigger();
        }

        assertThat(transactionCount(transactionId)).isZero();
        assertThat(auditCount(transactionId)).isZero();
    }

    @Test
    void rollsBackAuditWhenBusinessTransactionRollsBack() {
        UUID transactionId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    insertTransaction(transactionId);
                    service.append(transactionStatusDraft(transactionId));
                    status.setRollbackOnly();
                }
        );

        assertThat(transactionCount(transactionId)).isZero();
        assertThat(auditCount(transactionId)).isZero();
    }

    @Test
    void hidesBusinessDataAndAuditUntilRequiredTransactionCommits()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            Future<?> transaction = executor.submit(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                insertTransaction(transactionId);
                                service.append(
                                        transactionStatusDraft(transactionId)
                                );
                                flushed.countDown();
                                await(allowCommit);
                            })
            );

            assertThat(flushed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(transactionCount(transactionId)).isZero();
            assertThat(auditCount(transactionId)).isZero();
            allowCommit.countDown();
            transaction.get(20, TimeUnit.SECONDS);
            assertThat(transactionCount(transactionId)).isEqualTo(1);
            assertThat(auditCount(transactionId)).isEqualTo(1);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    private AuditLogDraft transactionStatusDraft(UUID transactionId) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                "finguardops-backend",
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transactionId,
                transactionId,
                null,
                "trace_audit_integration_01",
                object("processingStatus", "ANALYZED"),
                object("processingStatus", "HELD"),
                object("sourceRiskLevel", "CRITICAL")
        );
    }

    private UUID insertTransaction() {
        UUID transactionId = UUID.randomUUID();
        insertTransaction(transactionId);
        return transactionId;
    }

    private void insertTransaction(UUID transactionId) {
        jdbcTemplate.update(
                """
                        INSERT INTO financial_transaction (
                            transaction_id,
                            transaction_type,
                            amount,
                            currency_code,
                            occurred_at,
                            external_customer_ref,
                            sender_account_ref,
                            recipient_account_ref,
                            channel,
                            device_ref,
                            processing_status
                        ) VALUES (
                            ?, 'ACCOUNT_TRANSFER', ?, 'KRW', ?,
                            'customer_ref', 'sender_ref', 'recipient_ref',
                            'MOBILE_BANKING', 'device_ref', 'ANALYZED'
                        )
                        """,
                transactionId,
                BigDecimal.valueOf(10_000),
                Timestamp.from(BUSINESS_TIME)
        );
    }

    private UUID insertCase() {
        UUID caseId = UUID.randomUUID();
        Timestamp now = Timestamp.from(
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id,
                            case_status,
                            concurrency_version,
                            created_at,
                            last_changed_at
                        ) VALUES (?, 'OPEN', 0, ?, ?)
                        """,
                caseId,
                now,
                now
        );
        return caseId;
    }

    private RawAuditContract rawContract(
            String action,
            UUID transactionId,
            UUID caseId
    ) {
        return switch (action) {
            case "CASE_CREATED" -> new RawAuditContract(
                    action,
                    "CASE_REQUIRED_BY_RISK_POLICY",
                    "FRAUD_CASE",
                    caseId,
                    transactionId,
                    caseId,
                    null,
                    object("caseStatus", "OPEN"),
                    emptyObject()
            );
            case "CASE_TRANSACTION_LINKED" -> new RawAuditContract(
                    action,
                    "CASE_REQUIRED_BY_RISK_POLICY",
                    "FRAUD_CASE",
                    caseId,
                    transactionId,
                    caseId,
                    null,
                    emptyObject().put("linked", true),
                    emptyObject()
            );
            case "TRANSACTION_RISK_RESPONSE_APPLIED" ->
                    new RawAuditContract(
                            action,
                            "RISK_RESPONSE_DECIDED_BY_POLICY",
                            "FINANCIAL_TRANSACTION",
                            transactionId,
                            transactionId,
                            caseId,
                            object("riskResponseOutcome", "APPROVED"),
                            object("riskResponseOutcome", "HELD"),
                            object("sourceRiskLevel", "HIGH")
                    );
            case "TRANSACTION_STATUS_CHANGED" -> new RawAuditContract(
                    action,
                    "TRANSACTION_FINALIZED_BY_RISK_POLICY",
                    "FINANCIAL_TRANSACTION",
                    transactionId,
                    transactionId,
                    caseId,
                    object("processingStatus", "ANALYZED"),
                    object("processingStatus", "HELD"),
                    object("sourceRiskLevel", "CRITICAL")
            );
            default -> throw new IllegalArgumentException(
                    "unsupported test action: " + action
            );
        };
    }

    private void assertRejected(RawAuditContract contract) {
        assertThatThrownBy(() -> insertRawAudit(
                UUID.randomUUID(),
                "SYSTEM",
                "finguardops-backend",
                contract,
                "trace_audit_matrix_bad_01"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private JsonNode invalidAfter(String action) {
        return switch (action) {
            case "CASE_CREATED" -> object("caseStatus", "CLOSED");
            case "CASE_TRANSACTION_LINKED" ->
                    emptyObject().put("linked", false);
            case "TRANSACTION_RISK_RESPONSE_APPLIED" ->
                    object("riskResponseOutcome", "UNKNOWN");
            case "TRANSACTION_STATUS_CHANGED" ->
                    object("processingStatus", "UNKNOWN");
            default -> throw new IllegalArgumentException(
                    "unsupported test action: " + action
            );
        };
    }

    private String summaryField(String action) {
        return switch (action) {
            case "CASE_CREATED" -> "caseStatus";
            case "CASE_TRANSACTION_LINKED" -> "linked";
            case "TRANSACTION_RISK_RESPONSE_APPLIED" ->
                    "riskResponseOutcome";
            case "TRANSACTION_STATUS_CHANGED" -> "processingStatus";
            default -> throw new IllegalArgumentException(
                    "unsupported test action: " + action
            );
        };
    }

    private void insertRawAudit(
            UUID auditId,
            String actorType,
            String actorId,
            RawAuditContract contract,
            String traceId
    ) {
        insertRawAudit(
                auditId,
                actorType,
                actorId,
                contract.action(),
                contract.reasonCode(),
                contract.targetType(),
                contract.targetId(),
                contract.transactionId(),
                contract.caseId(),
                traceId,
                contract.before(),
                contract.after(),
                contract.metadata()
        );
    }

    private void insertRawCaseAudit(
            UUID auditId,
            String actorType,
            String actorId,
            String action,
            String reasonCode,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            String traceId,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        insertRawAudit(
                auditId,
                actorType,
                actorId,
                action,
                reasonCode,
                "FRAUD_CASE",
                targetId,
                transactionId,
                caseId,
                traceId,
                before,
                after,
                metadata
        );
    }

    private void insertRawAudit(
            UUID auditId,
            String actorType,
            String actorId,
            String action,
            String reasonCode,
            String targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            String traceId,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO audit_log (
                            audit_id, actor_type, actor_id, action,
                            reason_code, target_type, target_id,
                            transaction_id, case_id, trace_id,
                            before_value_summary, after_value_summary,
                            metadata, changed_at
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?::jsonb, ?::jsonb, ?::jsonb, ?
                        )
                        """,
                auditId,
                actorType,
                actorId,
                action,
                reasonCode,
                targetType,
                targetId,
                transactionId,
                caseId,
                traceId,
                json(before),
                json(after),
                json(metadata),
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS))
        );
    }

    private record RawAuditContract(
            String action,
            String reasonCode,
            String targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        private RawAuditContract withReasonCode(String value) {
            return new RawAuditContract(
                    action, value, targetType, targetId, transactionId,
                    caseId, before, after, metadata
            );
        }

        private RawAuditContract withTargetType(String value) {
            return new RawAuditContract(
                    action, reasonCode, value, targetId, transactionId,
                    caseId, before, after, metadata
            );
        }

        private RawAuditContract withTargetId(UUID value) {
            return new RawAuditContract(
                    action, reasonCode, targetType, value, transactionId,
                    caseId, before, after, metadata
            );
        }

        private RawAuditContract withTransactionId(UUID value) {
            return new RawAuditContract(
                    action, reasonCode, targetType, targetId, value,
                    caseId, before, after, metadata
            );
        }

        private RawAuditContract withCaseId(UUID value) {
            return new RawAuditContract(
                    action, reasonCode, targetType, targetId, transactionId,
                    value, before, after, metadata
            );
        }

        private RawAuditContract withAfter(JsonNode value) {
            return new RawAuditContract(
                    action, reasonCode, targetType, targetId, transactionId,
                    caseId, before, value, metadata
            );
        }

        private RawAuditContract withMetadata(JsonNode value) {
            return new RawAuditContract(
                    action, reasonCode, targetType, targetId, transactionId,
                    caseId, before, after, value
            );
        }
    }

    private String json(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private int transactionCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM financial_transaction
                        WHERE transaction_id = ?
                        """,
                Integer.class,
                transactionId
        );
    }

    private int auditCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE transaction_id = ?",
                Integer.class,
                transactionId
        );
    }

    private List<String> columns(String tableName) {
        return jdbcTemplate.queryForList(
                """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = ?
                        ORDER BY ordinal_position
                        """,
                String.class,
                tableName
        );
    }

    private List<String> constraints(String tableName) {
        return jdbcTemplate.queryForList(
                """
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = ?
                        ORDER BY constraint_name
                        """,
                String.class,
                tableName
        );
    }

    private List<String> indexes(String tableName) {
        return jdbcTemplate.queryForList(
                """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = ?
                        ORDER BY indexname
                        """,
                String.class,
                tableName
        );
    }

    private List<String> triggers(String tableName) {
        return jdbcTemplate.queryForList(
                """
                        SELECT trigger_name
                        FROM information_schema.triggers
                        WHERE event_object_schema = 'public'
                          AND event_object_table = ?
                        ORDER BY trigger_name
                        """,
                String.class,
                tableName
        );
    }

    private void installInsertRejectionTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_audit_log_test_insert()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced audit insert failure'
                        USING ERRCODE = '55000';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_audit_log_reject_test_insert
                    BEFORE INSERT ON audit_log
                    FOR EACH ROW
                    EXECUTE FUNCTION reject_audit_log_test_insert()
                """);
    }

    private void removeInsertRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tr_audit_log_reject_test_insert
                    ON audit_log
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS reject_audit_log_test_insert()"
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting",
                    exception
            );
        }
    }

    private ObjectNode emptyObject() {
        return objectMapper.createObjectNode();
    }

    private ObjectNode object(String field, String value) {
        return emptyObject().put(field, value);
    }
}
