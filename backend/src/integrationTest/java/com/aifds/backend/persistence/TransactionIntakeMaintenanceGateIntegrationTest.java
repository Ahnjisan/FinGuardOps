package com.aifds.backend.persistence;

import com.aifds.backend.idempotency.service.IdempotencyClaimWriter;
import com.aifds.backend.idempotency.service.IdempotencyMaintenanceSafetyVerifier;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.idempotency.service.TransactionIntakeMaintenanceGate;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionIntakeMaintenanceGateIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    @Autowired private IdempotencyMaintenanceSafetyVerifier verifier;
    @Autowired private TransactionIntakeMaintenanceGate gate;
    @Autowired private IdempotencyClaimWriter claimWriter;
    @Autowired private TransactionIntakeService intakeService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private IdempotencyRecoveryService recoveryService;

    @Test
    void oneShotRejectsWhenWebSessionsRemainEvenWithClosedGate() throws Exception {
        long recordId = claimWriter.createInProgress(
                "POST:/api/v1/transactions", "safety-" + UUID.randomUUID(),
                "b".repeat(64));
        verifier.close();
        try (Connection webSession = jdbcTemplate.getDataSource().getConnection()) {
            webSession.createStatement().execute(
                    "SET application_name = 'finguardops-web'");
            try {
                assertThat(recoveryService.recover(recordId, AuditActorType.SYSTEM,
                        AuditLog.SYSTEM_ACTOR_ID).decision()).isEqualTo(
                        IdempotencyRecoveryDecision.MAINTENANCE_PRECONDITION_FAILED);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT processing_status FROM idempotency_record WHERE id = ?
                        """, String.class, recordId)).isEqualTo("IN_PROGRESS");
            } finally {
                webSession.createStatement().execute("RESET application_name");
            }
        } finally {
            verifier.open();
        }
    }

    @Test
    void closedGateRejectsBeforeClaimAndReopenRestoresClaim() {
        String key = "maintenance-" + UUID.randomUUID();
        verifier.close();
        try {
            assertThat(intakeService.receive(key, request(), "trace-maintenance-123"))
                    .isInstanceOf(TransactionIntakeResult.ProviderUnavailable.class);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM idempotency_record
                    WHERE idempotency_key = ?
                    """, Integer.class, key)).isZero();
            assertThatThrownBy(() -> claimWriter.createInProgress(
                    "POST:/api/v1/transactions", key,
                    "a".repeat(64)))
                    .isInstanceOf(TransactionIntakeMaintenanceGate.ClosedException.class);
        } finally {
            verifier.open();
        }
        assertThat(claimWriter.createInProgress("POST:/api/v1/transactions",
                key, "a".repeat(64))).isPositive();
    }

    @Test
    void closingWaitsForInFlightSharedGateLock() throws Exception {
        CountDownLatch guarded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> inFlight = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                            ignored -> {
                                gate.requireOpen();
                                guarded.countDown();
                                try {
                                    assertThat(release.await(10, TimeUnit.SECONDS))
                                            .isTrue();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(exception);
                                }
                            }));
            assertThat(guarded.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> closing = executor.submit(verifier::close);
            assertThatThrownBy(() -> closing.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
            closing.get(10, TimeUnit.SECONDS);
            assertThat(gate.isOpen()).isFalse();
        } finally {
            release.countDown();
            verifier.open();
            executor.shutdownNow();
        }
    }

    private TransactionCreateRequest request() {
        return new TransactionCreateRequest(UUID.randomUUID().toString(),
                "ACCOUNT_TRANSFER", "1250000", "KRW",
                Instant.parse("2026-08-27T00:00:00Z").toString(),
                "customer_ref_provider_missing", "sender_ref_provider_missing",
                "recipient_ref_provider_missing", "MOBILE_BANKING",
                "device_ref_provider_missing");
    }
}
