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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionPersistenceIntegrationTest extends PostgresqlIntegrationTestSupport {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appliesAllMigrationsToEmptyPostgresql() {
        MigrationInfo[] appliedMigrations = flyway.info().applied();

        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(appliedMigrations).hasSize(4);
        assertThat(appliedMigrations[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(appliedMigrations[0].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(appliedMigrations[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(appliedMigrations[1].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(appliedMigrations[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(appliedMigrations[2].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(appliedMigrations[3].getVersion().getVersion()).isEqualTo("4");
        assertThat(appliedMigrations[3].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(tableNames()).contains(
                "financial_transaction",
                "idempotency_record",
                "behavior_event",
                "detection_result",
                "detection_evidence"
        );
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
                "adopted_detection_result_id",
                "risk_level",
                "risk_response_outcome",
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
        assertColumn(financialColumns, "adopted_detection_result_id", "bigint", "int8", true);
        assertColumn(financialColumns, "risk_level", "character varying", "varchar", true);
        assertColumn(financialColumns, "risk_response_outcome", "character varying", "varchar", true);
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
        assertThat(((Number) idempotencyColumns.get("finished_at")
                .get("datetime_precision")).intValue()).isEqualTo(6);
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
                        Map.entry("ck_financial_transaction_risk_level", "c"),
                        Map.entry("ck_financial_transaction_risk_response_outcome", "c"),
                        Map.entry("ck_financial_transaction_adopted_risk", "c"),
                        Map.entry("ck_financial_transaction_risk_response_mapping", "c"),
                        Map.entry("fk_financial_transaction_adopted_detection_result", "f"),
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
                "ix_financial_transaction_risk_occurred_at",
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
    void rejectsNonVersionFourAndInvalidVariantUuids() {
        Instant createdAt = Instant.now();
        Set<UUID> invalidTransactionIds = Set.of(
                UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
                UUID.fromString("6fa459ea-ee8a-3ca4-894e-db77e160355e"),
                UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"),
                UUID.fromString("2f4c0a4e-8a9d-4c2f-7a1b-7d6e5f430001")
        );

        for (UUID transactionId : invalidTransactionIds) {
            assertConstraintViolation(
                    () -> insertTransaction(
                            transactionId,
                            "ATM_WITHDRAWAL",
                            new BigDecimal("1000"),
                            "KRW",
                            createdAt.minusSeconds(1),
                            "cust_ref_uuid",
                            "acct_ref_uuid",
                            null,
                            "ATM",
                            null,
                            "RECEIVED",
                            createdAt
                    ),
                    "23514",
                    "ck_financial_transaction_uuid_v4"
            );
        }
    }

    @Test
    void acceptsAllApprovedTransactionTypeRecipientAndChannelCombinations() {
        Instant createdAt = Instant.now();

        insertTransaction(
                UUID.randomUUID(), "ACCOUNT_TRANSFER", new BigDecimal("1000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_account", "acct_ref_account_sender",
                "acct_ref_account_recipient", "MOBILE_BANKING", null, "RECEIVED", createdAt
        );
        insertTransaction(
                UUID.randomUUID(), "OPEN_BANKING_TRANSFER", new BigDecimal("2000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_open", "acct_ref_open_sender",
                "acct_ref_open_recipient", "OPEN_BANKING", null, "RECEIVED", createdAt
        );
        insertTransaction(
                UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("3000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_atm", "acct_ref_atm_sender",
                null, "ATM", null, "RECEIVED", createdAt
        );
        insertTransaction(
                UUID.randomUUID(), "LOAN_DISBURSED", new BigDecimal("4000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_loan", "acct_ref_loan_sender",
                null, "CORE_BANKING", null, "RECEIVED", createdAt
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transaction",
                Integer.class
        )).isEqualTo(4);
    }

    @Test
    void rejectsInvalidTransactionTypeRecipientAndChannelCombinations() {
        Instant createdAt = Instant.now();

        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ACCOUNT_TRANSFER", new BigDecimal("1000"), "KRW",
                        createdAt.minusSeconds(1), "cust_ref_missing", "acct_ref_missing",
                        null, "MOBILE_BANKING", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_type_contract"
        );
        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                        createdAt.minusSeconds(1), "cust_ref_forbidden", "acct_ref_forbidden",
                        "acct_ref_not_allowed", "ATM", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_type_contract"
        );
        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "OPEN_BANKING_TRANSFER", new BigDecimal("1000"), "KRW",
                        createdAt.minusSeconds(1), "cust_ref_channel", "acct_ref_channel",
                        "acct_ref_recipient", "MOBILE_BANKING", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_type_contract"
        );
    }

    @Test
    void rejectsNegativeAndFractionalAmountsAndStoresPositiveIntegerExactly() {
        Instant createdAt = Instant.now();

        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("-1"), "KRW",
                        createdAt.minusSeconds(1), "cust_ref_negative", "acct_ref_negative",
                        null, "ATM", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_amount"
        );
        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000.5"), "KRW",
                        createdAt.minusSeconds(1), "cust_ref_fraction", "acct_ref_fraction",
                        null, "ATM", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_amount"
        );

        long transactionPk = insertTransaction(
                UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("999999999999999"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_exact", "acct_ref_exact",
                null, "ATM", null, "RECEIVED", createdAt
        );
        BigDecimal storedAmount = jdbcTemplate.queryForObject(
                "SELECT amount FROM financial_transaction WHERE id = ?",
                BigDecimal.class,
                transactionPk
        );

        assertThat(storedAmount).isEqualByComparingTo("999999999999999.0000");
    }

    @Test
    void rejectsCurrencyOtherThanKrw() {
        Instant createdAt = Instant.now();

        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "USD",
                        createdAt.minusSeconds(1), "cust_ref_currency", "acct_ref_currency",
                        null, "ATM", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_currency"
        );
    }

    @Test
    void enforcesOccurredAtAgainstCreatedAtFiveMinuteBoundary() {
        Instant createdAt = Instant.now().minus(10, ChronoUnit.MINUTES);

        long acceptedTransactionPk = insertTransaction(
                UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                createdAt.plus(4, ChronoUnit.MINUTES), "cust_ref_four_minutes",
                "acct_ref_four_minutes", null, "ATM", null, "RECEIVED", createdAt
        );

        assertThat(acceptedTransactionPk).isPositive();
        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                        createdAt.plus(6, ChronoUnit.MINUTES), "cust_ref_six_minutes",
                        "acct_ref_six_minutes", null, "ATM", null, "RECEIVED", createdAt
                ),
                "23514",
                "ck_financial_transaction_occurred_at"
        );
    }

    @Test
    void rejectsBlankAndPaddedReferenceValues() {
        Instant createdAt = Instant.now();

        assertReferenceViolation(createdAt, "", "acct_ref_sender", "acct_ref_recipient", null,
                "ck_financial_transaction_refs");
        assertReferenceViolation(createdAt, " cust_ref", "acct_ref_sender", "acct_ref_recipient", null,
                "ck_financial_transaction_refs");
        assertReferenceViolation(createdAt, "cust_ref", "", "acct_ref_recipient", null,
                "ck_financial_transaction_refs");
        assertReferenceViolation(createdAt, "cust_ref", "acct_ref_sender ", "acct_ref_recipient", null,
                "ck_financial_transaction_refs");
        assertReferenceViolation(createdAt, "cust_ref", "acct_ref_sender", "", null,
                "ck_financial_transaction_type_contract");
        assertReferenceViolation(createdAt, "cust_ref", "acct_ref_sender", "acct_ref_recipient ", null,
                "ck_financial_transaction_type_contract");
        assertReferenceViolation(createdAt, "cust_ref", "acct_ref_sender", "acct_ref_recipient", "",
                "ck_financial_transaction_device_ref");
        assertReferenceViolation(createdAt, "cust_ref", "acct_ref_sender", "acct_ref_recipient", " device_ref",
                "ck_financial_transaction_device_ref");
    }

    @Test
    void rejectsValidationFailedAsPersistentTransactionStatus() {
        Instant createdAt = Instant.now();

        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                        createdAt.minusSeconds(1), "cust_ref_status", "acct_ref_status",
                        null, "ATM", null, "VALIDATION_FAILED", createdAt
                ),
                "23514",
                "ck_financial_transaction_processing_status"
        );
    }

    @Test
    void enforcesIdempotencyKeyLengthBoundaries() {
        insertInProgressIdempotencyRecord("a".repeat(8));
        insertInProgressIdempotencyRecord("b".repeat(128));

        assertConstraintViolation(
                () -> insertInProgressIdempotencyRecord("c".repeat(7)),
                "23514",
                "ck_idempotency_record_key_length"
        );
        assertSqlStateViolation(
                () -> insertInProgressIdempotencyRecord("d".repeat(129)),
                "22001"
        );
    }

    @Test
    void enforcesIdempotencyKeyAllowedCharacters() {
        insertInProgressIdempotencyRecord("Az09._:-");

        for (String invalidKey : Set.of("bad key1", "가나다라마바사아", "bad/key1", "bad\\key1")) {
            assertConstraintViolation(
                    () -> insertInProgressIdempotencyRecord(invalidKey),
                    "23514",
                    "ck_idempotency_record_key_characters"
            );
        }
    }

    @Test
    void enforcesSha256RequestFingerprintFormat() {
        insertInProgressIdempotencyRecord(
                "POST:/api/v1/transactions",
                "valid-fingerprint-key",
                "0123456789abcdef".repeat(4),
                null
        );

        for (String invalidFingerprint : Set.of(
                "A".repeat(64),
                "g".repeat(64),
                "a".repeat(63)
        )) {
            assertConstraintViolation(
                    () -> insertInProgressIdempotencyRecord(
                            "POST:/api/v1/transactions",
                            "invalid-fingerprint-" + UUID.randomUUID(),
                            invalidFingerprint,
                            null
                    ),
                    "23514",
                    "ck_idempotency_record_fingerprint"
            );
        }
    }

    @Test
    void rejectsLinkingSameTransactionToMultipleIdempotencyRecords() {
        Instant createdAt = Instant.now();
        long transactionPk = insertTransaction(
                UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_link", "acct_ref_link",
                null, "ATM", null, "RECEIVED", createdAt
        );
        insertInProgressIdempotencyRecord(
                "POST:/api/v1/transactions",
                "first-link-key",
                "e".repeat(64),
                transactionPk
        );

        assertConstraintViolation(
                () -> insertInProgressIdempotencyRecord(
                        "POST:/api/v1/transactions",
                        "second-link-key",
                        "f".repeat(64),
                        transactionPk
                ),
                "23505",
                "uq_idempotency_record_transaction"
        );
    }

    @Test
    void acceptsValidStateFieldCombinationsAndExactTwentyFourHourExpiration() {
        Instant createdAt = Instant.now();
        long transactionPk = insertTransaction(
                UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_states", "acct_ref_states",
                null, "ATM", null, "RECEIVED", createdAt
        );
        insertInProgressIdempotencyRecord("in-progress-state-key");
        jdbcTemplate.update("""
                        INSERT INTO idempotency_record (
                            operation_scope,
                            idempotency_key,
                            request_fingerprint,
                            processing_status,
                            financial_transaction_id,
                            response_snapshot,
                            finished_at
                        ) VALUES (?, ?, ?, 'COMPLETED', ?, '{}'::jsonb, CURRENT_TIMESTAMP)
                        """,
                "POST:/api/v1/transactions",
                "completed-state-key",
                "1".repeat(64),
                transactionPk
        );
        jdbcTemplate.update("""
                        INSERT INTO idempotency_record (
                            operation_scope,
                            idempotency_key,
                            request_fingerprint,
                            processing_status,
                            failure_code,
                            finished_at
                        ) VALUES (?, ?, ?, 'FAILED', ?, CURRENT_TIMESTAMP)
                        """,
                "POST:/api/v1/transactions",
                "failed-state-key",
                "2".repeat(64),
                "DEPENDENCY_TIMEOUT"
        );

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_record
                WHERE expires_at = created_at + INTERVAL '24 hours'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectsInvalidStateFieldCombinations() {
        Instant createdAt = Instant.now();
        long transactionPk = insertTransaction(
                UUID.randomUUID(), "ATM_WITHDRAWAL", new BigDecimal("1000"), "KRW",
                createdAt.minusSeconds(1), "cust_ref_invalid_states", "acct_ref_invalid_states",
                null, "ATM", null, "RECEIVED", createdAt
        );

        assertConstraintViolation(
                () -> jdbcTemplate.update("""
                                INSERT INTO idempotency_record (
                                    operation_scope,
                                    idempotency_key,
                                    request_fingerprint,
                                    processing_status,
                                    response_snapshot
                                ) VALUES (?, ?, ?, 'IN_PROGRESS', '{}'::jsonb)
                                """,
                        "POST:/api/v1/transactions",
                        "invalid-in-progress-state",
                        "3".repeat(64)
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
                                    finished_at
                                ) VALUES (?, ?, ?, 'FAILED', CURRENT_TIMESTAMP)
                                """,
                        "POST:/api/v1/transactions",
                        "invalid-failed-state",
                        "4".repeat(64)
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
                                    financial_transaction_id,
                                    response_snapshot,
                                    finished_at
                                ) VALUES (?, ?, ?, 'COMPLETED', ?, '[]'::jsonb, CURRENT_TIMESTAMP)
                                """,
                        "POST:/api/v1/transactions",
                        "invalid-completed-snapshot",
                        "5".repeat(64),
                        transactionPk
                ),
                "23514",
                "ck_idempotency_record_state_fields"
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
        IdempotencyRecord idempotencyRecord = IdempotencyRecord.inProgress(
                "POST:/api/v1/transactions",
                "entity-test-" + UUID.randomUUID(),
                "c".repeat(64)
        );
        entityManager.persist(idempotencyRecord);
        entityManager.flush();
        idempotencyRecord.complete(transaction, responseSnapshot, Instant.now());
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
        assertThat(loadedIdempotency.getFinishedAt())
                .isAfterOrEqualTo(loadedIdempotency.getCreatedAt());
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
                            numeric_scale,
                            datetime_precision
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

    private long insertTransaction(
            UUID transactionId,
            String transactionType,
            BigDecimal amount,
            String currencyCode,
            Instant occurredAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            String channel,
            String deviceRef,
            String processingStatus,
            Instant createdAt
    ) {
        Long id = jdbcTemplate.queryForObject("""
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
                            processing_status,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                transactionId,
                transactionType,
                amount,
                currencyCode,
                Timestamp.from(occurredAt),
                externalCustomerRef,
                senderAccountRef,
                recipientAccountRef,
                channel,
                deviceRef,
                processingStatus,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        return id;
    }

    private void assertReferenceViolation(
            Instant createdAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            String deviceRef,
            String expectedConstraint
    ) {
        assertConstraintViolation(
                () -> insertTransaction(
                        UUID.randomUUID(),
                        "ACCOUNT_TRANSFER",
                        new BigDecimal("1000"),
                        "KRW",
                        createdAt.minusSeconds(1),
                        externalCustomerRef,
                        senderAccountRef,
                        recipientAccountRef,
                        "MOBILE_BANKING",
                        deviceRef,
                        "RECEIVED",
                        createdAt
                ),
                "23514",
                expectedConstraint
        );
    }

    private void insertInProgressIdempotencyRecord(String idempotencyKey) {
        insertInProgressIdempotencyRecord(
                "POST:/api/v1/transactions",
                idempotencyKey,
                "d".repeat(64),
                null
        );
    }

    private void insertInProgressIdempotencyRecord(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint,
            Long financialTransactionId
    ) {
        jdbcTemplate.update("""
                        INSERT INTO idempotency_record (
                            operation_scope,
                            idempotency_key,
                            request_fingerprint,
                            processing_status,
                            financial_transaction_id
                        ) VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                        """,
                operationScope,
                idempotencyKey,
                requestFingerprint,
                financialTransactionId
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

    private void assertSqlStateViolation(Runnable operation, String expectedSqlState) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    Throwable rootCause = mostSpecificCause(exception);

                    assertThat(rootCause).isInstanceOf(SQLException.class);
                    SQLException sqlException = (SQLException) rootCause;
                    assertThat(sqlException.getSQLState()).isEqualTo(expectedSqlState);
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
