package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.fraudcase.exception.FraudCaseConsistencyException;
import com.aifds.backend.fraudcase.service.FraudCaseLinkResult;
import com.aifds.backend.fraudcase.service.FraudCasePersistenceService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudCasePersistenceIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-18T01:00:00Z");

    @Autowired
    private FraudCasePersistenceService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Flyway flyway;

    @Test
    void appliesFreshV1ThroughV11SchemaWithApprovedConstraintsAndIndexes() {
        assertThat(flyway.info().applied()).hasSize(11);
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("11");
        assertThat(flyway.info().current().getDescription())
                .isEqualTo("extend audit log for fraud case workflow");
        assertThat(columns("fraud_case")).containsExactlyInAnyOrder(
                "id",
                "case_id",
                "case_status",
                "final_disposition",
                "assignee_ref",
                "review_started_at",
                "closed_at",
                "concurrency_version",
                "created_at",
                "last_changed_at"
        );
        assertThat(columns("case_transaction")).containsExactlyInAnyOrder(
                "id",
                "fraud_case_id",
                "financial_transaction_id",
                "linked_at"
        );
        assertThat(constraints("fraud_case")).contains(
                "pk_fraud_case",
                "uq_fraud_case_case_id",
                "ck_fraud_case_uuid_v4",
                "ck_fraud_case_status",
                "ck_fraud_case_final_disposition",
                "ck_fraud_case_state_fields",
                "ck_fraud_case_in_review_assignee",
                "ck_fraud_case_concurrency_version"
        );
        assertThat(constraints("case_transaction")).contains(
                "pk_case_transaction",
                "uq_case_transaction_case_transaction",
                "fk_case_transaction_case",
                "fk_case_transaction_transaction"
        );
        assertThat(indexes("fraud_case")).contains(
                "uq_fraud_case_case_id",
                "ix_fraud_case_status_last_changed",
                "ix_fraud_case_last_changed"
        );
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'fraud_case'
                          AND indexname = 'ix_fraud_case_last_changed'
                        """,
                String.class
        )).isEqualTo(
                "CREATE INDEX ix_fraud_case_last_changed "
                        + "ON public.fraud_case USING btree "
                        + "(last_changed_at, id)"
        );
        assertThat(indexes("case_transaction")).contains(
                "uq_case_transaction_case_transaction",
                "ix_case_transaction_transaction_case"
        );
    }

    @ParameterizedTest
    @EnumSource(value = RiskLevel.class, names = {"HIGH", "CRITICAL"})
    void createsCaseAndFirstRelationshipForHighRiskTransaction(
            RiskLevel riskLevel
    ) {
        TransactionFixture transaction = insertAnalyzedTransaction(riskLevel);

        FraudCaseLinkResult result =
                service.createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                );

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.caseId().version()).isEqualTo(4);
        assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
        assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT case_status
                        FROM fraud_case
                        WHERE case_id = ?
                        """,
                String.class,
                result.caseId()
        )).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT processing_status
                        FROM financial_transaction
                        WHERE transaction_id = ?
                        """,
                String.class,
                transaction.transactionId()
        )).isEqualTo("ANALYZED");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT risk_response_outcome
                        FROM financial_transaction
                        WHERE transaction_id = ?
                        """,
                String.class,
                transaction.transactionId()
        )).isNull();
        assertThat(caseAndLinkTimestamps(result.caseId()))
                .containsOnly(result.linkedAt());
    }

    @Test
    void returnsSameActiveCaseForExactRepeatedCall() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );

        FraudCaseLinkResult first =
                service.createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                );
        FraudCaseLinkResult second =
                service.createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                );

        assertThat(first.newlyCreated()).isTrue();
        assertThat(second.newlyCreated()).isFalse();
        assertThat(second.caseId()).isEqualTo(first.caseId());
        assertThat(second.linkedAt()).isEqualTo(first.linkedAt());
        assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
        assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
    }

    @Test
    void concurrentCallsConvergeToOneCaseAndOneRelationship()
            throws Exception {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.CRITICAL
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<FraudCaseLinkResult> first = executor.submit(() ->
                    invokeWhenReleased(transaction.transactionId(), ready, start)
            );
            Future<FraudCaseLinkResult> second = executor.submit(() ->
                    invokeWhenReleased(transaction.transactionId(), ready, start)
            );
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            FraudCaseLinkResult firstResult = first.get(
                    30,
                    TimeUnit.SECONDS
            );
            FraudCaseLinkResult secondResult = second.get(
                    30,
                    TimeUnit.SECONDS
            );

            assertThat(secondResult.caseId())
                    .isEqualTo(firstResult.caseId());
            assertThat(List.of(
                    firstResult.newlyCreated(),
                    secondResult.newlyCreated()
            )).containsExactlyInAnyOrder(true, false);
            assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
            assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMultipleActiveCasesWithoutCreatingAnother() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        insertCaseRelationship(transaction, "OPEN", null);
        insertCaseRelationship(transaction, "OPEN", null);

        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                )
        ).isInstanceOf(FraudCaseConsistencyException.class)
                .hasMessage("Transaction has multiple active cases");

        assertThat(caseCount(transaction.transactionId())).isEqualTo(2);
        assertThat(linkCount(transaction.transactionId())).isEqualTo(2);
    }

    @Test
    void doesNotReuseClosedCaseAndCreatesNewOpenCase() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        UUID closedCaseId = insertCaseRelationship(
                transaction,
                "CLOSED",
                "NORMAL"
        );

        FraudCaseLinkResult result =
                service.createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                );

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.caseId()).isNotEqualTo(closedCaseId);
        assertThat(caseCount(transaction.transactionId())).isEqualTo(2);
        assertThat(activeCaseCount(transaction.transactionId())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = RiskLevel.class, names = {"LOW", "MEDIUM"})
    void rejectsTransactionsWhoseDecisionDoesNotRequireCase(
            RiskLevel riskLevel
    ) {
        TransactionFixture transaction = insertAnalyzedTransaction(riskLevel);

        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Transaction risk decision does not require a case"
                );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fraud_case",
                Integer.class
        )).isZero();
    }

    @Test
    void rollsBackCaseWhenFirstRelationshipInsertFails() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        installRelationshipRejectionTrigger();
        try {
            assertThatThrownBy(() ->
                    service.createOrReuseForHighRiskTransaction(
                            transaction.transactionId()
                    )
            ).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(caseCount(transaction.transactionId())).isZero();
            assertThat(linkCount(transaction.transactionId())).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fraud_case",
                    Integer.class
            )).isZero();
        } finally {
            removeRelationshipRejectionTrigger();
        }
    }

    @Test
    void hidesCaseAndRelationshipUntilRequiredTransactionCommits()
            throws Exception {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            Future<FraudCaseLinkResult> result = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        FraudCaseLinkResult linked =
                                service.createOrReuseForHighRiskTransaction(
                                        transaction.transactionId()
                                );
                        flushed.countDown();
                        await(allowCommit);
                        return linked;
                    })
            );

            assertThat(flushed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(caseCount(transaction.transactionId())).isZero();
            assertThat(linkCount(transaction.transactionId())).isZero();
            allowCommit.countDown();
            assertThat(result.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
            assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesStateForeignKeyAndUniqueConstraints() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        Timestamp now = Timestamp.from(
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, 'UNKNOWN', 0, ?, ?)
                        """,
                UUID.randomUUID(),
                now,
                now
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, 'IN_REVIEW', 0, ?, ?)
                        """,
                UUID.randomUUID(),
                now,
                now
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, 'CLOSED', 0, ?, ?)
                        """,
                UUID.randomUUID(),
                now,
                now
        )).isInstanceOf(DataIntegrityViolationException.class);

        UUID caseId = insertCaseRelationship(transaction, "OPEN", null);
        Long casePk = jdbcTemplate.queryForObject(
                "SELECT id FROM fraud_case WHERE case_id = ?",
                Long.class,
                caseId
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO case_transaction (
                            fraud_case_id,
                            financial_transaction_id,
                            linked_at
                        ) VALUES (?, ?, ?)
                        """,
                casePk,
                transaction.internalId(),
                now
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO case_transaction (
                            fraud_case_id,
                            financial_transaction_id,
                            linked_at
                        ) VALUES (?, ?, ?)
                        """,
                Long.MAX_VALUE,
                transaction.internalId(),
                now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doesNotSelectAnotherTransactionsActiveCase() {
        TransactionFixture first = insertAnalyzedTransaction(RiskLevel.HIGH);
        TransactionFixture second = insertAnalyzedTransaction(RiskLevel.HIGH);

        FraudCaseLinkResult firstResult =
                service.createOrReuseForHighRiskTransaction(
                        first.transactionId()
                );
        FraudCaseLinkResult secondResult =
                service.createOrReuseForHighRiskTransaction(
                        second.transactionId()
                );

        assertThat(secondResult.caseId()).isNotEqualTo(firstResult.caseId());
        assertThat(caseCount(first.transactionId())).isEqualTo(1);
        assertThat(caseCount(second.transactionId())).isEqualTo(1);
    }

    private FraudCaseLinkResult invokeWhenReleased(
            UUID transactionId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return service.createOrReuseForHighRiskTransaction(transactionId);
    }

    private TransactionFixture insertAnalyzedTransaction(RiskLevel riskLevel) {
        UUID transactionId = UUID.randomUUID();
        Long transactionPk = jdbcTemplate.queryForObject(
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
                            'MOBILE_BANKING', 'device_ref', 'RECEIVED'
                        )
                        RETURNING id
                        """,
                Long.class,
                transactionId,
                BigDecimal.valueOf(10_000),
                Timestamp.from(OCCURRED_AT)
        );
        Long detectionResultPk = jdbcTemplate.queryForObject(
                """
                        INSERT INTO detection_result (
                            detection_result_id,
                            financial_transaction_id,
                            detection_result_version,
                            analysis_status,
                            risk_score,
                            risk_level,
                            rule_set_version,
                            scoring_policy_version,
                            feature_version,
                            evaluation_cutoff_at,
                            analysis_started_at,
                            analysis_completed_at,
                            analysis_trace_id
                        ) VALUES (
                            ?, ?, 1, 'COMPLETED', 90, ?,
                            'rule-set-v1', 'scoring-v1', 'feature-v1',
                            ?, ?, ?, 'trace_case_integration_01'
                        )
                        RETURNING id
                        """,
                Long.class,
                UUID.randomUUID(),
                transactionPk,
                riskLevel.name(),
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT.plusSeconds(1)),
                Timestamp.from(OCCURRED_AT.plusSeconds(2))
        );
        jdbcTemplate.update(
                """
                        UPDATE financial_transaction
                        SET processing_status = 'ANALYZED',
                            adopted_detection_result_id = ?,
                            risk_level = ?
                        WHERE id = ?
                        """,
                detectionResultPk,
                riskLevel.name(),
                transactionPk
        );
        return new TransactionFixture(transactionId, transactionPk);
    }

    private UUID insertCaseRelationship(
            TransactionFixture transaction,
            String status,
            String disposition
    ) {
        UUID caseId = UUID.randomUUID();
        Timestamp now = Timestamp.from(
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        Long casePk = jdbcTemplate.queryForObject(
                """
                        INSERT INTO fraud_case (
                            case_id,
                            case_status,
                            final_disposition,
                            closed_at,
                            concurrency_version,
                            created_at,
                            last_changed_at
                        ) VALUES (?, ?, ?, ?, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                caseId,
                status,
                disposition,
                "CLOSED".equals(status) ? now : null,
                now,
                now
        );
        jdbcTemplate.update(
                """
                        INSERT INTO case_transaction (
                            fraud_case_id,
                            financial_transaction_id,
                            linked_at
                        ) VALUES (?, ?, ?)
                        """,
                casePk,
                transaction.internalId(),
                now
        );
        return caseId;
    }

    private List<Instant> caseAndLinkTimestamps(UUID caseId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT fraud_case.created_at,
                               fraud_case.last_changed_at,
                               case_transaction.linked_at
                        FROM fraud_case
                        JOIN case_transaction
                          ON case_transaction.fraud_case_id = fraud_case.id
                        WHERE fraud_case.case_id = ?
                        """,
                (resultSet, rowNumber) -> List.of(
                        resultSet.getTimestamp(1).toInstant(),
                        resultSet.getTimestamp(2).toInstant(),
                        resultSet.getTimestamp(3).toInstant()
                ),
                caseId
        );
    }

    private int caseCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM fraud_case
                        JOIN case_transaction
                          ON case_transaction.fraud_case_id = fraud_case.id
                        JOIN financial_transaction
                          ON financial_transaction.id =
                             case_transaction.financial_transaction_id
                        WHERE financial_transaction.transaction_id = ?
                        """,
                Integer.class,
                transactionId
        );
    }

    private int activeCaseCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM fraud_case
                        JOIN case_transaction
                          ON case_transaction.fraud_case_id = fraud_case.id
                        JOIN financial_transaction
                          ON financial_transaction.id =
                             case_transaction.financial_transaction_id
                        WHERE financial_transaction.transaction_id = ?
                          AND fraud_case.case_status IN (
                              'OPEN',
                              'IN_REVIEW',
                              'ADDITIONAL_INFORMATION_REQUIRED'
                          )
                        """,
                Integer.class,
                transactionId
        );
    }

    private int linkCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM case_transaction
                        JOIN financial_transaction
                          ON financial_transaction.id =
                             case_transaction.financial_transaction_id
                        WHERE financial_transaction.transaction_id = ?
                        """,
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

    private void installRelationshipRejectionTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_case_transaction_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'test relationship rejection'
                        USING ERRCODE = '23514';
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_reject_case_transaction_insert
                BEFORE INSERT ON case_transaction
                FOR EACH ROW
                EXECUTE FUNCTION reject_case_transaction_insert()
                """);
    }

    private void removeRelationshipRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_reject_case_transaction_insert
                ON case_transaction
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS reject_case_transaction_insert()"
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
                    "Interrupted while waiting for latch",
                    exception
            );
        }
    }

    private record TransactionFixture(UUID transactionId, long internalId) {
    }
}
