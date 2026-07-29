package com.aifds.backend.persistence;

import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.exception.BehaviorEventTransactionNotFoundException;
import com.aifds.backend.behavior.exception.DuplicateBehaviorEventException;
import com.aifds.backend.behavior.service.BehaviorEventIntakeResult;
import com.aifds.backend.behavior.service.BehaviorEventIntakeService;
import com.aifds.backend.behavior.validation.BehaviorEventValidationException;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BehaviorEventIntakeIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String VALID_FINGERPRINT = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BehaviorEventIntakeService service;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Test
    void schemaMatchesEntityColumnConstraintAndMinimalIndexContract() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, udt_name, is_nullable,
                       character_maximum_length, is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'behavior_event'
                ORDER BY ordinal_position
                """);

        assertThat(columns).hasSize(11);
        assertColumn(columns, "id", "bigint", "int8", "NO", null, "YES");
        assertColumn(columns, "event_id", "uuid", "uuid", "NO", null, "NO");
        assertColumn(columns, "event_type", "character varying", "varchar",
                "NO", 32, "NO");
        assertColumn(columns, "occurred_at", "timestamp with time zone",
                "timestamptz", "NO", null, "NO");
        assertColumn(columns, "external_customer_ref", "character varying",
                "varchar", "NO", 128, "NO");
        assertColumn(columns, "account_ref", "character varying", "varchar",
                "YES", 128, "NO");
        assertColumn(columns, "device_ref", "character varying", "varchar",
                "YES", 128, "NO");
        assertColumn(columns, "beneficiary_ref", "character varying",
                "varchar", "YES", 128, "NO");
        assertColumn(columns, "financial_transaction_id", "bigint", "int8",
                "YES", null, "NO");
        assertColumn(columns, "request_fingerprint", "character varying",
                "varchar", "NO", 64, "NO");
        assertColumn(columns, "created_at", "timestamp with time zone",
                "timestamptz", "NO", null, "NO");

        Set<String> constraints = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'behavior_event'::regclass
                """, String.class));
        assertThat(constraints).containsExactlyInAnyOrder(
                "pk_behavior_event",
                "uq_behavior_event_event_id",
                "fk_behavior_event_transaction",
                "ck_behavior_event_uuid_v4",
                "ck_behavior_event_type",
                "ck_behavior_event_external_customer_ref",
                "ck_behavior_event_account_ref",
                "ck_behavior_event_device_ref",
                "ck_behavior_event_beneficiary_ref",
                "ck_behavior_event_type_fields",
                "ck_behavior_event_occurred_at",
                "ck_behavior_event_fingerprint"
        );

        Set<String> indexes = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'behavior_event'
                """, String.class));
        assertThat(indexes).containsExactlyInAnyOrder(
                "pk_behavior_event",
                "uq_behavior_event_event_id",
                "ix_behavior_event_transaction"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE indexname = 'ix_behavior_event_transaction'
                """, String.class)).contains(
                "WHERE (financial_transaction_id IS NOT NULL)"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT confdeltype::text
                FROM pg_constraint
                WHERE conname = 'fk_behavior_event_transaction'
                """, String.class)).isEqualTo("r");
    }

    @Test
    void databaseAcceptsExactlyNineEventTypesWithTheirNullContracts() {
        Long transactionPk = saveTransaction(
                TransactionType.ACCOUNT_TRANSFER,
                "customer",
                "sender",
                "recipient"
        ).getId();
        Instant now = Instant.now();

        for (BehaviorEventType type : BehaviorEventType.values()) {
            String account = switch (type) {
                case BENEFICIARY_REGISTERED, TRANSFER_LIMIT_CHANGED,
                        TRANSFER_REQUESTED, ATM_WITHDRAWAL_REQUESTED ->
                        "sender";
                default -> null;
            };
            String device = switch (type) {
                case LOGIN, DEVICE_REGISTERED -> "device";
                default -> null;
            };
            String beneficiary = type
                    == BehaviorEventType.BENEFICIARY_REGISTERED
                    ? "beneficiary"
                    : null;
            Long relatedTransaction = switch (type) {
                case TRANSFER_REQUESTED, ATM_WITHDRAWAL_REQUESTED ->
                        transactionPk;
                default -> null;
            };
            insertBehaviorEvent(
                    UUID.randomUUID(),
                    type.name(),
                    now,
                    "customer",
                    account,
                    device,
                    beneficiary,
                    relatedTransaction,
                    VALID_FINGERPRINT,
                    now
            );
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM behavior_event",
                Long.class
        )).isEqualTo(9);
    }

    @Test
    void databaseRejectsInvalidUuidEnumReferencesTypeFieldsTimeAndFingerprint() {
        Instant now = Instant.now();
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.fromString(
                                "00000000-0000-1000-8000-000000000000"
                        ),
                        "LOGIN_FAILED", now, "customer", null, null, null,
                        null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_uuid_v4"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.fromString(
                                "00000000-0000-4000-0000-000000000000"
                        ),
                        "LOGIN_FAILED", now, "customer", null, null, null,
                        null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_uuid_v4"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "UNKNOWN", now, "customer", null,
                        null, null, null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_type"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN_FAILED", now, " padded",
                        null, null, null, null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_external_customer_ref"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN_FAILED", now, "customer",
                        "account ", null, null, null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_account_ref"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN_FAILED", now, "customer",
                        null, " device", null, null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_device_ref"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "BENEFICIARY_REGISTERED", now,
                        "customer", "account", null, "beneficiary ", null,
                        VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_beneficiary_ref"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN", now, "customer", null,
                        null, null, null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_type_fields"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "BENEFICIARY_REGISTERED", now,
                        "customer", "account", null, null, null,
                        VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_type_fields"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "TRANSFER_REQUESTED", now,
                        "customer", "account", null, null, null,
                        VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_type_fields"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "PASSWORD_CHANGED", now,
                        "customer", null, null, "forbidden", null,
                        VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_type_fields"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN_FAILED",
                        now.plusSeconds(301), "customer", null, null, null,
                        null, VALID_FINGERPRINT, now
                ),
                "ck_behavior_event_occurred_at"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN_FAILED", now, "customer",
                        null, null, null, null, "A".repeat(64), now
                ),
                "ck_behavior_event_fingerprint"
        );
    }

    @Test
    void foreignKeyIsRestrictedAndEventIdIsUnique() {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        FinancialTransaction transaction = saveTransaction(
                TransactionType.ACCOUNT_TRANSFER,
                "customer",
                "sender",
                "recipient"
        );
        insertBehaviorEvent(
                eventId, "LOGIN_FAILED", now, "customer", null, null, null,
                transaction.getId(), VALID_FINGERPRINT, now
        );

        assertConstraint(
                () -> insertBehaviorEvent(
                        eventId, "LOGIN_FAILED", now, "customer", null, null,
                        null, null, VALID_FINGERPRINT, now
                ),
                "uq_behavior_event_event_id"
        );
        assertConstraint(
                () -> jdbcTemplate.update(
                        "DELETE FROM financial_transaction WHERE id = ?",
                        transaction.getId()
                ),
                "fk_behavior_event_transaction"
        );
        assertConstraint(
                () -> insertBehaviorEvent(
                        UUID.randomUUID(), "LOGIN_FAILED", now, "customer",
                        null, null, null, Long.MAX_VALUE, VALID_FINGERPRINT, now
                ),
                "fk_behavior_event_transaction"
        );
    }

    @Test
    void firstIntakeReplayConflictAndDifferentEventIdKeepExpectedRowCounts() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(1);
        BehaviorEventCreateRequest request = request(
                eventId,
                occurredAt,
                "customer"
        );

        assertThat(service.receive(request))
                .isInstanceOf(BehaviorEventIntakeResult.Created.class);
        assertThat(service.receive(request))
                .isInstanceOf(BehaviorEventIntakeResult.Replay.class);
        assertThat(rowCount()).isEqualTo(1);
        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT account_ref, device_ref, beneficiary_ref,
                       financial_transaction_id, request_fingerprint
                FROM behavior_event
                WHERE event_id = ?
                """, eventId);
        assertThat(stored.get("account_ref")).isNull();
        assertThat(stored.get("device_ref")).isNull();
        assertThat(stored.get("beneficiary_ref")).isNull();
        assertThat(stored.get("financial_transaction_id")).isNull();
        assertThat(stored.get("request_fingerprint").toString())
                .matches("[0-9a-f]{64}");

        BehaviorEventCreateRequest conflict = request(
                eventId,
                occurredAt,
                "different-customer"
        );
        assertThatThrownBy(() -> service.receive(conflict))
                .isInstanceOf(DuplicateBehaviorEventException.class);
        assertThat(rowCount()).isEqualTo(1);

        assertThat(service.receive(request(
                UUID.randomUUID(),
                occurredAt,
                "customer"
        ))).isInstanceOf(BehaviorEventIntakeResult.Created.class);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void transactionLinkPersistsAndBusinessConsistencyIsValidated() {
        FinancialTransaction transaction = saveTransaction(
                TransactionType.OPEN_BANKING_TRANSFER,
                "customer",
                "sender",
                "recipient"
        );
        BehaviorEventCreateRequest matching = transferRequest(
                UUID.randomUUID(),
                transaction.getTransactionId(),
                "customer",
                "sender"
        );

        service.receive(matching);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT financial_transaction_id
                FROM behavior_event
                WHERE event_id = ?
                """, Long.class, UUID.fromString(matching.eventId())))
                .isEqualTo(transaction.getId());

        assertThatThrownBy(() -> service.receive(transferRequest(
                UUID.randomUUID(),
                transaction.getTransactionId(),
                "other-customer",
                "sender"
        ))).isInstanceOf(BehaviorEventValidationException.class);
        assertThatThrownBy(() -> service.receive(transferRequest(
                UUID.randomUUID(),
                transaction.getTransactionId(),
                "customer",
                "recipient"
        ))).isInstanceOf(BehaviorEventValidationException.class);
        FinancialTransaction atm = saveTransaction(
                TransactionType.ATM_WITHDRAWAL,
                "customer",
                "sender",
                null
        );
        assertThatThrownBy(() -> service.receive(transferRequest(
                UUID.randomUUID(),
                atm.getTransactionId(),
                "customer",
                "sender"
        ))).isInstanceOf(BehaviorEventValidationException.class);
        assertThatThrownBy(() -> service.receive(transferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer",
                "sender"
        ))).isInstanceOf(BehaviorEventTransactionNotFoundException.class);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateOneRowAndReturnOneCreateWithReplays()
            throws Exception {
        BehaviorEventCreateRequest request = request(
                UUID.randomUUID(),
                Instant.now().minusSeconds(1),
                "customer"
        );
        int taskCount = 8;
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        List<Future<BehaviorEventIntakeResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.receive(request);
                }));
            }
            ready.await();
            start.countDown();

            List<BehaviorEventIntakeResult> results = new ArrayList<>();
            for (Future<BehaviorEventIntakeResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results.stream().filter(
                    BehaviorEventIntakeResult.Created.class::isInstance
            )).hasSize(1);
            assertThat(results.stream().filter(
                    BehaviorEventIntakeResult.Replay.class::isInstance
            )).hasSize(taskCount - 1);
            assertThat(rowCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertColumn(
            List<Map<String, Object>> columns,
            String name,
            String dataType,
            String udtName,
            String nullable,
            Integer length,
            String identity
    ) {
        Map<String, Object> column = columns.stream()
                .filter(candidate -> name.equals(candidate.get("column_name")))
                .findFirst()
                .orElseThrow();
        assertThat(column.get("data_type")).isEqualTo(dataType);
        assertThat(column.get("udt_name")).isEqualTo(udtName);
        assertThat(column.get("is_nullable")).isEqualTo(nullable);
        assertThat(column.get("character_maximum_length")).isEqualTo(length);
        assertThat(column.get("is_identity")).isEqualTo(identity);
    }

    private FinancialTransaction saveTransaction(
            TransactionType type,
            String customer,
            String sender,
            String recipient
    ) {
        TransactionChannel channel = switch (type) {
            case ACCOUNT_TRANSFER -> TransactionChannel.MOBILE_BANKING;
            case OPEN_BANKING_TRANSFER -> TransactionChannel.OPEN_BANKING;
            case ATM_WITHDRAWAL -> TransactionChannel.ATM;
            case LOAN_DISBURSED -> TransactionChannel.CORE_BANKING;
        };
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                UUID.randomUUID(),
                type,
                BigDecimal.ONE,
                "KRW",
                Instant.now().minusSeconds(2),
                customer,
                sender,
                recipient,
                channel,
                null
        ));
    }

    private static BehaviorEventCreateRequest request(
            UUID eventId,
            Instant occurredAt,
            String customer
    ) {
        return new BehaviorEventCreateRequest(
                eventId.toString(),
                "LOGIN_FAILED",
                occurredAt.toString(),
                customer,
                null,
                null,
                null,
                null
        );
    }

    private static BehaviorEventCreateRequest transferRequest(
            UUID eventId,
            UUID transactionId,
            String customer,
            String account
    ) {
        return new BehaviorEventCreateRequest(
                eventId.toString(),
                "TRANSFER_REQUESTED",
                Instant.now().minusSeconds(1).toString(),
                customer,
                account,
                null,
                transactionId.toString(),
                null
        );
    }

    private long rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM behavior_event",
                Long.class
        );
    }

    private void insertBehaviorEvent(
            UUID eventId,
            String eventType,
            Instant occurredAt,
            String externalCustomerRef,
            String accountRef,
            String deviceRef,
            String beneficiaryRef,
            Long transactionId,
            String fingerprint,
            Instant createdAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO behavior_event (
                            event_id,
                            event_type,
                            occurred_at,
                            external_customer_ref,
                            account_ref,
                            device_ref,
                            beneficiary_ref,
                            financial_transaction_id,
                            request_fingerprint,
                            created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                eventId,
                eventType,
                Timestamp.from(occurredAt),
                externalCustomerRef,
                accountRef,
                deviceRef,
                beneficiaryRef,
                transactionId,
                fingerprint,
                Timestamp.from(createdAt)
        );
    }

    private void assertConstraint(Runnable operation, String constraint) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    Throwable current = exception;
                    while (current.getCause() != null
                            && current.getCause() != current) {
                        current = current.getCause();
                    }
                    assertThat(current).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) current).getMessage())
                            .contains(constraint);
                });
    }
}
