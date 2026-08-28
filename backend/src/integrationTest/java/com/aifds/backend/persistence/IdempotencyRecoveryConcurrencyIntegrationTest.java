package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionSynchronousProcessingCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyRecoveryConcurrencyIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-28T00:00:00Z");

    @Autowired
    private IdempotencyRecoveryService recoveryService;
    @MockitoSpyBean
    private RiskResponseFinalizationService finalizationService;
    @Autowired
    private TransactionIntakeService transactionIntakeService;
    @Autowired
    private TransactionRequestFingerprint requestFingerprint;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TransactionSynchronousProcessingCoordinator coordinator;

    @Test
    void concurrentRecoveryHasOneWinnerAndOneTerminalRejection()
            throws Exception {
        RecoveryFixture fixture = finalizedFixture(RiskLevel.CRITICAL);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Callable<IdempotencyRecoveryResult> invocation = () -> {
                start.await(10, TimeUnit.SECONDS);
                return recoveryService.recover(
                        fixture.recordId(),
                        AuditActorType.SYSTEM,
                        AuditLog.SYSTEM_ACTOR_ID
                );
            };
            Future<IdempotencyRecoveryResult> first = executor.submit(
                    invocation
            );
            Future<IdempotencyRecoveryResult> second = executor.submit(
                    invocation
            );

            assertThat(List.of(
                    first.get(20, TimeUnit.SECONDS).decision(),
                    second.get(20, TimeUnit.SECONDS).decision()
            )).containsExactlyInAnyOrder(
                    IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP,
                    IdempotencyRecoveryDecision.ALREADY_TERMINAL
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        )).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForList(
                """
                        SELECT recovery_decision || ':' || audit_result
                        FROM idempotency_recovery_audit_log
                        WHERE idempotency_record_id = ?
                        ORDER BY id
                        """,
                String.class,
                fixture.recordId()
        )).containsExactlyInAnyOrder(
                "RECOVERABLE_COMPLETION_GAP:RECOVERED",
                "ALREADY_TERMINAL:REJECTED"
        );
    }

    @Test
    void publicReplayRacingRecoveryNeverAcquiresNewProcessing()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionFingerprintInput input = fingerprintInput(transactionId);
        String key = "recovery-race-" + UUID.randomUUID();
        RecoveryFixture fixture = finalizedFixture(
                RiskLevel.HIGH,
                input,
                key,
                requestFingerprint.calculate(input)
        );
        when(coordinator.isAvailable()).thenReturn(true);
        clearInvocations(finalizationService, coordinator);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<IdempotencyRecoveryResult> recovery = executor.submit(
                    () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return recoveryService.recover(
                                fixture.recordId(),
                                AuditActorType.SYSTEM,
                                AuditLog.SYSTEM_ACTOR_ID
                        );
                    }
            );
            Future<TransactionIntakeResult> replay = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return transactionIntakeService.receive(
                        key,
                        request(input),
                        "trace_recovery_public_race_01"
                );
            });

            assertThat(recovery.get(20, TimeUnit.SECONDS).decision())
                    .isEqualTo(
                            IdempotencyRecoveryDecision
                                    .RECOVERABLE_COMPLETION_GAP
                    );
            TransactionIntakeResult publicResult = replay.get(
                    20,
                    TimeUnit.SECONDS
            );
            assertThat(publicResult)
                    .isInstanceOfAny(
                            TransactionIntakeResult.InProgress.class,
                            TransactionIntakeResult.CompletedReplay.class
                    )
                    .isNotInstanceOf(TransactionIntakeResult.Received.class);
            if (publicResult
                    instanceof TransactionIntakeResult.CompletedReplay replayed) {
                assertThat(replayed.httpStatus()).isEqualTo(201);
                assertThat(replayed.snapshot().transactionId())
                        .isEqualTo(transactionId);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        )).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM idempotency_recovery_audit_log
                        WHERE idempotency_record_id = ?
                          AND recovery_decision =
                              'RECOVERABLE_COMPLETION_GAP'
                          AND audit_result = 'RECOVERED'
                        """,
                Integer.class,
                fixture.recordId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transaction",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detection_result",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM case_transaction",
                Integer.class
        )).isEqualTo(1);
        verify(coordinator).isAvailable();
        verify(coordinator, never()).process(
                anyLong(),
                any(),
                anyString()
        );
        verify(finalizationService, never()).finalizeRiskResponse(any());
    }

    private RecoveryFixture finalizedFixture(RiskLevel riskLevel) {
        UUID transactionId = UUID.randomUUID();
        TransactionFingerprintInput input = fingerprintInput(transactionId);
        return finalizedFixture(
                riskLevel,
                input,
                "recovery-concurrent-" + UUID.randomUUID(),
                "e".repeat(64)
        );
    }

    private RecoveryFixture finalizedFixture(
            RiskLevel riskLevel,
            TransactionFingerprintInput input,
            String key,
            String fingerprint
    ) {
        long transactionPk = jdbcTemplate.queryForObject(
                """
                        INSERT INTO financial_transaction (
                            transaction_id, transaction_type, amount,
                            currency_code, occurred_at,
                            external_customer_ref, sender_account_ref,
                            recipient_account_ref, channel, device_ref,
                            processing_status
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                        RETURNING id
                        """,
                Long.class,
                input.transactionId(),
                input.transactionType().name(),
                input.amount(),
                input.currencyCode(),
                Timestamp.from(input.occurredAt()),
                input.externalCustomerRef(),
                input.senderAccountRef(),
                input.recipientAccountRef(),
                input.channel().name(),
                input.deviceRef()
        );
        long detectionPk = jdbcTemplate.queryForObject(
                """
                        INSERT INTO detection_result (
                            detection_result_id, financial_transaction_id,
                            detection_result_version, analysis_status,
                            risk_score, risk_level, rule_set_version,
                            scoring_policy_version, feature_version,
                            evaluation_cutoff_at, analysis_started_at,
                            analysis_completed_at, analysis_trace_id
                        ) VALUES (
                            ?, ?, 1, 'COMPLETED', 90, ?,
                            'rule-set-v1', 'scoring-v1', 'feature-v1',
                            ?, ?, ?, 'trace_recovery_concurrency_01'
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
                detectionPk,
                riskLevel.name(),
                transactionPk
        );
        long recordId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO idempotency_record (
                            operation_scope, idempotency_key,
                            request_fingerprint, processing_status,
                            financial_transaction_id
                        ) VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                        RETURNING id
                        """,
                Long.class,
                OPERATION_SCOPE,
                key,
                fingerprint,
                transactionPk
        );
        finalizationService.finalizeRiskResponse(input.transactionId());
        return new RecoveryFixture(recordId, input.transactionId());
    }

    private TransactionFingerprintInput fingerprintInput(UUID transactionId) {
        return new TransactionFingerprintInput(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(125_000),
                "KRW",
                OCCURRED_AT,
                "customer_ref_recovery_concurrent",
                "sender_ref_recovery_concurrent",
                "recipient_ref_recovery_concurrent",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_recovery_concurrent"
        );
    }

    private TransactionCreateRequest request(TransactionFingerprintInput input) {
        return new TransactionCreateRequest(
                input.transactionId().toString(),
                input.transactionType().name(),
                input.amount().toPlainString(),
                input.currencyCode(),
                input.occurredAt().toString(),
                input.externalCustomerRef(),
                input.senderAccountRef(),
                input.recipientAccountRef(),
                input.channel().name(),
                input.deviceRef()
        );
    }

    private record RecoveryFixture(
            long recordId,
            UUID transactionId
    ) {
    }
}
