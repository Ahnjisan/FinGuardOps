package com.aifds.backend.persistence;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionPersistenceIntegrationTest {

    private static final String INSERT_ATM_TRANSACTION = """
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
                device_ref
            ) VALUES (?, 'ATM_WITHDRAWAL', ?, 'KRW', CURRENT_TIMESTAMP, ?, ?, NULL, 'ATM', NULL)
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appliesV1MigrationToEmptyPostgresql() {
        MigrationInfo[] appliedMigrations = flyway.info().applied();

        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(appliedMigrations).hasSize(1);
        assertThat(appliedMigrations[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(appliedMigrations[0].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(tableNames()).contains("financial_transaction", "idempotency_record");
    }

    @Test
    void createsColumnsWithContractTypesAndNullability() {
        Map<String, Map<String, Object>> financialColumns = columns("financial_transaction");
        Map<String, Map<String, Object>> idempotencyColumns = columns("idempotency_record");

        assertThat(financialColumns.keySet()).containsExactlyInAnyOrder(
                "id",
                "transaction_id",
                "transaction_type",
                "amount",
                "currency_code",
                "occurred_at",
                "external_customer_ref",
                "sender_account_ref",
                "recipient_account_ref",
                "channel",
                "device_ref",
                "processing_status",
                "version",
                "created_at",
                "updated_at"
        );
        assertColumn(financialColumns, "id", "bigint", "int8", false);
        assertThat(financialColumns.get("id").get("is_identity")).isEqualTo("YES");
        assertColumn(financialColumns, "transaction_id", "uuid", "uuid", false);
        assertColumn(financialColumns, "transaction_type", "character varying", "varchar", false);
        assertColumn(financialColumns, "amount", "numeric", "numeric", false);
        assertThat(financialColumns.get("amount").get("numeric_precision")).isEqualTo(19);
        assertThat(financialColumns.get("amount").get("numeric_scale")).isEqualTo(4);
        assertColumn(financialColumns, "occurred_at", "timestamp with time zone", "timestamptz", false);
        assertColumn(financialColumns, "recipient_account_ref", "character varying", "varchar", true);
        assertColumn(financialColumns, "device_ref", "character varying", "varchar", true);
        assertColumn(financialColumns, "version", "bigint", "int8", false);
        assertColumn(financialColumns, "created_at", "timestamp with time zone", "timestamptz", false);
        assertColumn(financialColumns, "updated_at", "timestamp with time zone", "timestamptz", false);

        assertThat(idempotencyColumns.keySet()).containsExactlyInAnyOrder(
                "id",
                "operation_scope",
                "idempotency_key",
                "request_fingerprint",
                "processing_status",
                "financial_transaction_id",
                "response_snapshot",
                "failure_code",
                "expires_at",
                "created_at",
                "updated_at",
                "finished_at"
        );
        assertColumn(idempotencyColumns, "id", "bigint", "int8", false);
        assertThat(idempotencyColumns.get("id").get("is_identity")).isEqualTo("YES");
        assertColumn(idempotencyColumns, "operation_scope", "character varying", "varchar", false);
        assertColumn(idempotencyColumns, "request_fingerprint", "character varying", "varchar", false);
        assertColumn(idempotencyColumns, "financial_transaction_id", "bigint", "int8", true);
        assertColumn(idempotencyColumns, "response_snapshot", "jsonb", "jsonb", true);
        assertColumn(idempotencyColumns, "expires_at", "timestamp with time zone", "timestamptz", false);
        assertColumn(idempotencyColumns, "finished_at", "timestamp with time zone", "timestamptz", true);
    }

    @Test
    void createsNamedPrimaryUniqueCheckAndForeignKeyConstraints() {
        assertThat(constraints("financial_transaction")).containsExactlyInAnyOrderEntriesOf(
                Map.ofEntries(
                        Map.entry("pk_financial_transaction", "p"),
                        Map.entry("uq_financial_transaction_transaction_id", "u"),
                        Map.entry("ck_financial_transaction_uuid_v4", "c"),
                        Map.entry("ck_financial_transaction_type", "c"),
                        Map.entry("ck_financial_transaction_amount", "c"),
                        Map.entry("ck_financial_transaction_currency", "c"),
                        Map.entry("ck_financial_transaction_occurred_at", "c"),
                        Map.entry("ck_financial_transaction_refs", "c"),
                        Map.entry("ck_financial_transaction_type_contract", "c"),
                        Map.entry("ck_financial_transaction_device_ref", "c"),
                        Map.entry("ck_financial_transaction_processing_status", "c"),
                        Map.entry("ck_financial_transaction_version", "c"),
                        Map.entry("ck_financial_transaction_timestamps", "c")
                )
        );
        assertThat(constraints("idempotency_record")).containsExactlyInAnyOrderEntriesOf(
                Map.ofEntries(
                        Map.entry("pk_idempotency_record", "p"),
                        Map.entry("uq_idempotency_record_scope_key", "u"),
                        Map.entry("uq_idempotency_record_transaction", "u"),
                        Map.entry("fk_idempotency_record_transaction", "f"),
                        Map.entry("ck_idempotency_record_scope", "c"),
                        Map.entry("ck_idempotency_record_key_length", "c"),
                        Map.entry("ck_idempotency_record_key_characters", "c"),
                        Map.entry("ck_idempotency_record_fingerprint", "c"),
                        Map.entry("ck_idempotency_record_status", "c"),
                        Map.entry("ck_idempotency_record_state_fields", "c"),
                        Map.entry("ck_idempotency_record_expiration", "c"),
                        Map.entry("ck_idempotency_record_timestamps", "c")
                )
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT confdeltype::text
                FROM pg_constraint
                WHERE conname = 'fk_idempotency_record_transaction'
                """, String.class)).isEqualTo("r");
    }

    @Test
    void createsContractIndexesWithoutDuplicates() {
        Map<String, String> indexes = indexes();

        assertThat(indexes.keySet()).containsExactlyInAnyOrder(
                "pk_financial_transaction",
                "uq_financial_transaction_transaction_id",
                "ix_financial_transaction_occurred_at",
                "ix_financial_transaction_type_occurred_at",
                "ix_financial_transaction_status_occurred_at",
                "ix_financial_transaction_customer_occurred_at",
                "ix_financial_transaction_sender_occurred_at",
                "ix_financial_transaction_recipient_occurred_at",
                "pk_idempotency_record",
                "uq_idempotency_record_scope_key",
                "uq_idempotency_record_transaction",
                "ix_idempotency_record_expires_at",
                "ix_idempotency_record_status_updated_at"
        );
        assertThat(indexes.get("ix_financial_transaction_recipient_occurred_at"))
                .contains("WHERE (recipient_account_ref IS NOT NULL)");
    }

    @Test
    void enforcesTransactionUniqueAndCheckConstraints() {
        UUID transactionId = UUID.randomUUID();
        insertAtmTransaction(transactionId, new BigDecimal("1000"));

        assertConstraintViolation(
                () -> insertAtmTransaction(transactionId, new BigDecimal("1000")),
                "23505",
                "uq_financial_transaction_transaction_id"
        );
        assertConstraintViolation(
                () -> insertAtmTransaction(UUID.randomUUID(), BigDecimal.ZERO),
                "23514",
                "ck_financial_transaction_amount"
        );
    }

    @Test
    void enforcesIdempotencyUniqueCheckAndForeignKeyConstraints() {
        String key = "idem-key-" + UUID.randomUUID();
        insertInProgressIdempotencyRecord(key);

        assertConstraintViolation(
                () -> insertInProgressIdempotencyRecord(key),
                "23505",
                "uq_idempotency_record_scope_key"
        );
        assertConstraintViolation(
                () -> jdbcTemplate.update("""
                                INSERT INTO idempotency_record (
                                    operation_scope,
                                    idempotency_key,
                                    request_fingerprint,
                                    processing_status
                                ) VALUES (?, ?, ?, 'COMPLETED')
                                """,
                        "POST:/api/v1/transactions",
                        "invalid-completed-" + UUID.randomUUID(),
                        "a".repeat(64)
                ),
                "23514",
                "ck_idempotency_record_state_fields"
        );
        assertConstraintViolation(
                () -> jdbcTemplate.update("""
                                INSERT INTO idempotency_record (
                                    operation_scope,
                                    idempotency_key,
                                    request_fingerprint,
                                    processing_status,
                                    financial_transaction_id
                                ) VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                                """,
                        "POST:/api/v1/transactions",
                        "missing-transaction-" + UUID.randomUUID(),
                        "b".repeat(64),
                        Long.MAX_VALUE
                ),
                "23503",
                "fk_idempotency_record_transaction"
        );
    }

    @Test
    @Transactional
    void persistsAndLoadsEntitiesThroughEntityManager() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction transaction = new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES),
                "cust_ref_integration",
                "acct_ref_sender",
                "acct_ref_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_integration"
        );
        entityManager.persist(transaction);

        ObjectNode responseSnapshot = objectMapper.createObjectNode()
                .put("transactionId", transactionId.toString())
                .put("processingStatus", "RECEIVED");
        IdempotencyRecord idempotencyRecord = IdempotencyRecord.completed(
                "POST:/api/v1/transactions",
                "entity-test-" + UUID.randomUUID(),
                "c".repeat(64),
                transaction,
                responseSnapshot
        );
        entityManager.persist(idempotencyRecord);
        entityManager.flush();

        Long transactionPk = transaction.getId();
        Long idempotencyPk = idempotencyRecord.getId();
        entityManager.clear();

        FinancialTransaction loadedTransaction =
                entityManager.find(FinancialTransaction.class, transactionPk);
        IdempotencyRecord loadedIdempotency =
                entityManager.find(IdempotencyRecord.class, idempotencyPk);

        assertThat(loadedTransaction.getTransactionId()).isEqualTo(transactionId);
        assertThat(loadedTransaction.getTransactionType()).isEqualTo(TransactionType.ACCOUNT_TRANSFER);
        assertThat(loadedTransaction.getAmount()).isEqualByComparingTo("1250000.0000");
        assertThat(loadedTransaction.getCurrencyCode()).isEqualTo("KRW");
        assertThat(loadedTransaction.getChannel()).isEqualTo(TransactionChannel.MOBILE_BANKING);
        assertThat(loadedTransaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(loadedTransaction.getVersion()).isZero();
        assertThat(loadedTransaction.getCreatedAt()).isNotNull();
        assertThat(loadedTransaction.getUpdatedAt()).isEqualTo(loadedTransaction.getCreatedAt());

        assertThat(loadedIdempotency.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        assertThat(loadedIdempotency.getFinancialTransaction().getId()).isEqualTo(transactionPk);
        assertThat(loadedIdempotency.getResponseSnapshot().get("transactionId").asText())
                .isEqualTo(transactionId.toString());
        assertThat(loadedIdempotency.getExpiresAt())
                .isEqualTo(loadedIdempotency.getCreatedAt().plus(24, ChronoUnit.HOURS));
        assertThat(loadedIdempotency.getFinishedAt()).isEqualTo(loadedIdempotency.getCreatedAt());
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                """, String.class));
    }

    private Map<String, Map<String, Object>> columns(String tableName) {
        return jdbcTemplate.queryForList("""
                        SELECT
                            column_name,
                            data_type,
                            udt_name,
                            is_nullable,
                            is_identity,
                            numeric_precision,
                            numeric_scale
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = ?
                        """,
                tableName
        ).stream().collect(Collectors.toMap(
                row -> (String) row.get("column_name"),
                Function.identity()
        ));
    }

    private Map<String, String> constraints(String tableName) {
        return jdbcTemplate.query("""
                        SELECT constraint_record.conname, constraint_record.contype::text
                        FROM pg_constraint constraint_record
                        JOIN pg_class table_record
                          ON table_record.oid = constraint_record.conrelid
                        JOIN pg_namespace schema_record
                          ON schema_record.oid = table_record.relnamespace
                        WHERE schema_record.nspname = current_schema()
                          AND table_record.relname = ?
                          AND constraint_record.contype IN ('p', 'u', 'c', 'f')
                        """,
                resultSet -> {
                    Map<String, String> result = new HashMap<>();
                    while (resultSet.next()) {
                        result.put(
                                resultSet.getString("conname"),
                                resultSet.getString("contype")
                        );
                    }
                    return result;
                },
                tableName
        );
    }

    private Map<String, String> indexes() {
        return jdbcTemplate.query("""
                        SELECT indexname, indexdef
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND tablename IN ('financial_transaction', 'idempotency_record')
                        """,
                resultSet -> {
                    Map<String, String> result = new HashMap<>();
                    while (resultSet.next()) {
                        result.put(resultSet.getString("indexname"), resultSet.getString("indexdef"));
                    }
                    return result;
                }
        );
    }

    private void assertColumn(
            Map<String, Map<String, Object>> columns,
            String columnName,
            String dataType,
            String udtName,
            boolean nullable
    ) {
        Map<String, Object> column = columns.get(columnName);

        assertThat(column).isNotNull();
        assertThat(column.get("data_type")).isEqualTo(dataType);
        assertThat(column.get("udt_name")).isEqualTo(udtName);
        assertThat(column.get("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
    }

    private void insertAtmTransaction(UUID transactionId, BigDecimal amount) {
        jdbcTemplate.update(
                INSERT_ATM_TRANSACTION,
                transactionId,
                amount,
                "cust_ref_" + UUID.randomUUID(),
                "acct_ref_" + UUID.randomUUID()
        );
    }

    private void insertInProgressIdempotencyRecord(String idempotencyKey) {
        jdbcTemplate.update("""
                        INSERT INTO idempotency_record (
                            operation_scope,
                            idempotency_key,
                            request_fingerprint,
                            processing_status
                        ) VALUES (?, ?, ?, 'IN_PROGRESS')
                        """,
                "POST:/api/v1/transactions",
                idempotencyKey,
                "d".repeat(64)
        );
    }

    private void assertConstraintViolation(
            Runnable operation,
            String expectedSqlState,
            String expectedConstraint
    ) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    Throwable rootCause = mostSpecificCause(exception);

                    assertThat(rootCause).isInstanceOf(SQLException.class);
                    SQLException sqlException = (SQLException) rootCause;
                    assertThat(sqlException.getSQLState()).isEqualTo(expectedSqlState);
                    assertThat(sqlException.getMessage()).contains(expectedConstraint);
                });
    }

    private Throwable mostSpecificCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
