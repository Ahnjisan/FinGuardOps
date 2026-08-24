package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.fraudcase.service.FraudCaseLinkResult;
import com.aifds.backend.fraudcase.service.FraudCasePersistenceService;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.service.RiskResponseFinalizationResult;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RiskResponseFinalizationIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-24T01:00:00Z");

    @Autowired
    private RiskResponseFinalizationService service;

    @Autowired
    private FraudCasePersistenceService fraudCasePersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @ParameterizedTest
    @MethodSource("risksWithoutCases")
    void finalizesLowAndMediumWithTwoTransactionAudits(
            RiskLevel riskLevel,
            TransactionProcessingStatus expectedStatus,
            RiskResponseOutcome expectedOutcome
    ) {
        TransactionFixture transaction = insertAnalyzedTransaction(riskLevel);

        RiskResponseFinalizationResult result = service.finalizeRiskResponse(
                transaction.transactionId()
        );

        assertThat(result.processingStatus()).isEqualTo(expectedStatus);
        assertThat(result.riskResponseOutcome()).isEqualTo(expectedOutcome);
        assertThat(result.caseId()).isNull();
        assertThat(result.caseCreated()).isFalse();
        assertTransactionState(transaction, expectedStatus, expectedOutcome);
        assertThat(caseCount(transaction.transactionId())).isZero();
        assertThat(linkCount(transaction.transactionId())).isZero();
        assertThat(auditActions(transaction.transactionId())).containsExactly(
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED.name(),
                AuditAction.TRANSACTION_STATUS_CHANGED.name()
        );
    }

    @ParameterizedTest
    @MethodSource("risksWithCases")
    void finalizesHighAndCriticalWithNewCaseAndFourAudits(
            RiskLevel riskLevel,
            TransactionProcessingStatus expectedStatus,
            RiskResponseOutcome expectedOutcome
    ) {
        TransactionFixture transaction = insertAnalyzedTransaction(riskLevel);

        RiskResponseFinalizationResult result = service.finalizeRiskResponse(
                transaction.transactionId()
        );

        assertThat(result.processingStatus()).isEqualTo(expectedStatus);
        assertThat(result.riskResponseOutcome()).isEqualTo(expectedOutcome);
        assertThat(result.caseId()).isNotNull();
        assertThat(result.caseCreated()).isTrue();
        assertTransactionState(transaction, expectedStatus, expectedOutcome);
        assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
        assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
        assertThat(auditActions(transaction.transactionId())).containsExactly(
                AuditAction.CASE_CREATED.name(),
                AuditAction.CASE_TRANSACTION_LINKED.name(),
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED.name(),
                AuditAction.TRANSACTION_STATUS_CHANGED.name()
        );
    }

    @Test
    void reusesExistingActiveCaseWithoutDuplicateCaseAudits() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        FraudCaseLinkResult existing = fraudCasePersistenceService
                .createOrReuseForHighRiskTransaction(
                        transaction.transactionId()
                );

        RiskResponseFinalizationResult result = service.finalizeRiskResponse(
                transaction.transactionId()
        );

        assertThat(result.caseId()).isEqualTo(existing.caseId());
        assertThat(result.caseCreated()).isFalse();
        assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
        assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
        assertThat(auditActions(transaction.transactionId())).containsExactly(
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED.name(),
                AuditAction.TRANSACTION_STATUS_CHANGED.name()
        );
    }

    @Test
    void concurrentFinalizationAllowsExactlyOneRequestToSucceed()
            throws Exception {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.CRITICAL
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Invocation> first = executor.submit(() -> invokeWhenReleased(
                    transaction.transactionId(),
                    ready,
                    start
            ));
            Future<Invocation> second = executor.submit(() -> invokeWhenReleased(
                    transaction.transactionId(),
                    ready,
                    start
            ));
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Invocation> invocations = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );

            assertThat(invocations).filteredOn(
                    invocation -> invocation.result() != null
            ).hasSize(1);
            assertThat(invocations).filteredOn(
                    invocation -> invocation.failure()
                            instanceof IllegalStateException
            ).hasSize(1);
            assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
            assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
            assertThat(auditActions(transaction.transactionId())).hasSize(4);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rollsBackEverythingWhenCaseRelationshipInsertFails() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        installRelationshipRejectionTrigger();
        try {
            assertThatThrownBy(() -> service.finalizeRiskResponse(
                    transaction.transactionId()
            )).isInstanceOf(DataIntegrityViolationException.class);

            assertUnfinalizedWithoutSideEffects(transaction);
        } finally {
            removeRelationshipRejectionTrigger();
        }
    }

    @Test
    void rollsBackCaseAndLinkWhenTransactionUpdateFails() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.CRITICAL
        );
        installTransactionUpdateRejectionTrigger();
        try {
            assertThatThrownBy(() -> service.finalizeRiskResponse(
                    transaction.transactionId()
            )).isInstanceOf(DataIntegrityViolationException.class);

            assertUnfinalizedWithoutSideEffects(transaction);
        } finally {
            removeTransactionUpdateRejectionTrigger();
        }
    }

    @Test
    void rollsBackAllBusinessChangesWhenAuditInsertFails() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        installAuditRejectionTrigger();
        try {
            assertThatThrownBy(() -> service.finalizeRiskResponse(
                    transaction.transactionId()
            )).isInstanceOf(DataIntegrityViolationException.class);

            assertUnfinalizedWithoutSideEffects(transaction);
        } finally {
            removeAuditRejectionTrigger();
        }
    }

    @Test
    void rollsBackFirstFlushedAuditWhenFollowUpAuditInsertFails() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.CRITICAL
        );
        try {
            installFollowUpAuditRejectionTrigger();

            assertThatThrownBy(() -> service.finalizeRiskResponse(
                    transaction.transactionId()
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(
                            "test follow-up audit rejection after "
                                    + "CASE_CREATED"
                    );

            entityManager.clear();
            assertUnfinalizedWithoutSideEffects(transaction);
        } finally {
            removeFollowUpAuditRejectionTrigger();
        }
    }

    @Test
    void hidesFlushedBusinessAndAuditRowsUntilOuterTransactionCommits()
            throws Exception {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.HIGH
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch finalized = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            Future<RiskResponseFinalizationResult> future = executor.submit(
                    () -> new TransactionTemplate(transactionManager).execute(
                            status -> {
                                RiskResponseFinalizationResult result =
                                        service.finalizeRiskResponse(
                                                transaction.transactionId()
                                        );
                                finalized.countDown();
                                await(allowCommit);
                                return result;
                            }
                    )
            );

            assertThat(finalized.await(20, TimeUnit.SECONDS)).isTrue();
            assertUnfinalizedWithoutSideEffects(transaction);
            allowCommit.countDown();
            assertThat(future.get(20, TimeUnit.SECONDS)).isNotNull();
            assertTransactionState(
                    transaction,
                    TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                    RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
            );
            assertThat(caseCount(transaction.transactionId())).isEqualTo(1);
            assertThat(linkCount(transaction.transactionId())).isEqualTo(1);
            assertThat(auditActions(transaction.transactionId())).hasSize(4);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsTerminalRecallAndLateExecutionWithoutMoreAudits() {
        TransactionFixture transaction = insertAnalyzedTransaction(
                RiskLevel.MEDIUM
        );
        service.finalizeRiskResponse(transaction.transactionId());

        assertThatThrownBy(() -> service.finalizeRiskResponse(
                transaction.transactionId()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Transaction processing status must be ANALYZED"
                );

        assertThat(auditActions(transaction.transactionId())).hasSize(2);
        assertThat(caseCount(transaction.transactionId())).isZero();
    }

    private Invocation invokeWhenReleased(
            UUID transactionId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        try {
            return new Invocation(
                    service.finalizeRiskResponse(transactionId),
                    null
            );
        } catch (RuntimeException exception) {
            return new Invocation(null, exception);
        }
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
        UUID detectionResultId = UUID.randomUUID();
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
                            ?, ?, ?, 'trace_finalization_integration_01'
                        )
                        RETURNING id
                        """,
                Long.class,
                detectionResultId,
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
        return new TransactionFixture(
                transactionId,
                detectionResultId
        );
    }

    private void assertTransactionState(
            TransactionFixture transaction,
            TransactionProcessingStatus expectedStatus,
            RiskResponseOutcome expectedOutcome
    ) {
        List<String> values = jdbcTemplate.queryForObject(
                """
                        SELECT processing_status, risk_response_outcome
                        FROM financial_transaction
                        WHERE transaction_id = ?
                        """,
                (resultSet, rowNumber) -> List.of(
                        resultSet.getString(1),
                        resultSet.getString(2)
                ),
                transaction.transactionId()
        );
        assertThat(values).containsExactly(
                expectedStatus.name(),
                expectedOutcome.name()
        );
    }

    private void assertUnfinalizedWithoutSideEffects(
            TransactionFixture transaction
    ) {
        Map<String, Object> state = jdbcTemplate.queryForMap(
                """
                        SELECT financial.processing_status,
                               financial.risk_response_outcome,
                               financial.risk_level
                                   AS transaction_risk_level,
                               result.detection_result_id,
                               result.analysis_status,
                               result.risk_level AS detection_risk_level
                        FROM financial_transaction financial
                        JOIN detection_result result
                          ON result.id =
                             financial.adopted_detection_result_id
                        WHERE financial.transaction_id = ?
                        """,
                transaction.transactionId()
        );
        assertThat(state.get("processing_status")).isEqualTo("ANALYZED");
        assertThat(state.get("risk_response_outcome")).isNull();
        assertThat(state.get("transaction_risk_level"))
                .isEqualTo(state.get("detection_risk_level"));
        assertThat(state.get("detection_result_id"))
                .isEqualTo(transaction.detectionResultId());
        assertThat(state.get("analysis_status")).isEqualTo("COMPLETED");
        assertThat(totalCaseCount()).isZero();
        assertThat(caseCount(transaction.transactionId())).isZero();
        assertThat(linkCount(transaction.transactionId())).isZero();
        assertThat(auditActions(transaction.transactionId())).isEmpty();
    }

    private int totalCaseCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fraud_case",
                Integer.class
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

    private List<String> auditActions(UUID transactionId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT action
                        FROM audit_log
                        WHERE transaction_id = ?
                        ORDER BY id
                        """,
                String.class,
                transactionId
        );
    }

    private void installRelationshipRejectionTrigger() {
        createRejectionFunction(
                "reject_finalization_relationship_insert",
                "test relationship rejection"
        );
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_reject_finalization_relationship_insert
                BEFORE INSERT ON case_transaction
                FOR EACH ROW
                EXECUTE FUNCTION reject_finalization_relationship_insert()
                """);
    }

    private void removeRelationshipRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS
                    tg_reject_finalization_relationship_insert
                ON case_transaction
                """);
        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS
                    reject_finalization_relationship_insert()
                """);
    }

    private void installTransactionUpdateRejectionTrigger() {
        createRejectionFunction(
                "reject_finalization_transaction_update",
                "test transaction update rejection"
        );
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_reject_finalization_transaction_update
                BEFORE UPDATE OF processing_status
                ON financial_transaction
                FOR EACH ROW
                WHEN (OLD.processing_status = 'ANALYZED'
                      AND NEW.processing_status <> 'ANALYZED')
                EXECUTE FUNCTION reject_finalization_transaction_update()
                """);
    }

    private void removeTransactionUpdateRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_reject_finalization_transaction_update
                ON financial_transaction
                """);
        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS reject_finalization_transaction_update()
                """);
    }

    private void installAuditRejectionTrigger() {
        createRejectionFunction(
                "reject_finalization_audit_insert",
                "test audit rejection"
        );
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_reject_finalization_audit_insert
                BEFORE INSERT ON audit_log
                FOR EACH ROW
                EXECUTE FUNCTION reject_finalization_audit_insert()
                """);
    }

    private void removeAuditRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_reject_finalization_audit_insert
                ON audit_log
                """);
        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS reject_finalization_audit_insert()
                """);
    }

    private void installFollowUpAuditRejectionTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_finalization_follow_up_audit_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF NEW.action = 'CASE_TRANSACTION_LINKED' THEN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM audit_log
                            WHERE transaction_id = NEW.transaction_id
                              AND action = 'CASE_CREATED'
                        ) THEN
                            RAISE EXCEPTION
                                'preceding CASE_CREATED audit was not flushed';
                        END IF;
                        RAISE EXCEPTION
                            'test follow-up audit rejection after CASE_CREATED'
                            USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END
                $function$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER
                    tg_reject_finalization_follow_up_audit_insert
                BEFORE INSERT ON audit_log
                FOR EACH ROW
                EXECUTE FUNCTION
                    reject_finalization_follow_up_audit_insert()
                """);
    }

    private void removeFollowUpAuditRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS
                    tg_reject_finalization_follow_up_audit_insert
                ON audit_log
                """);
        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS
                    reject_finalization_follow_up_audit_insert()
                """);
    }

    private void createRejectionFunction(String name, String message) {
        jdbcTemplate.execute("""
                CREATE FUNCTION %s()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    RAISE EXCEPTION '%s' USING ERRCODE = '23514';
                END
                $function$
                """.formatted(name, message));
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

    private static Stream<Arguments> risksWithoutCases() {
        return Stream.of(
                Arguments.of(
                        RiskLevel.LOW,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED
                ),
                Arguments.of(
                        RiskLevel.MEDIUM,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED_WITH_MONITORING
                )
        );
    }

    private static Stream<Arguments> risksWithCases() {
        return Stream.of(
                Arguments.of(
                        RiskLevel.HIGH,
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                        RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
                ),
                Arguments.of(
                        RiskLevel.CRITICAL,
                        TransactionProcessingStatus.HELD,
                        RiskResponseOutcome.HELD
                )
        );
    }

    private record TransactionFixture(
            UUID transactionId,
            UUID detectionResultId
    ) {
    }

    private record Invocation(
            RiskResponseFinalizationResult result,
            RuntimeException failure
    ) {
    }
}
