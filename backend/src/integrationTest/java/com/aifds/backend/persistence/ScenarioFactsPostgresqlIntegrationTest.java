package com.aifds.backend.persistence;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.scenario.ScenarioFactsService;
import com.aifds.backend.scenario.ScenarioRulePolicy;
import com.aifds.backend.scenario.mock.*;
import com.aifds.backend.transaction.entity.*;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioFactsPostgresqlIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired private FinancialTransactionRepository transactions;
    @Autowired private BehaviorEventRepository behaviors;
    @Autowired private MockFactIntakeService mocks;
    @Autowired private ScenarioFactsService factsService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void unrelatedScenarioMockVolumeCannotFailAtmEvaluation() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.plusSeconds(90);
        String customer = "atm-volume-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fds_mock_fact (fact_id, fact_type, external_customer_ref,
                  device_ref, occurred_at, assertion_state, request_fingerprint)
                SELECT md5(random()::text || seq::text)::uuid, 'APP_SECURITY', ?,
                  'unrelated-device', ?::timestamptz - interval '1 minute'
                    - seq * interval '1 second',
                  'CLEAR', repeat('a', 64)
                FROM generate_series(1, 1001) AS seq
                """, customer, Timestamp.from(now));
        FinancialTransaction atm = transactions.saveAndFlush(new FinancialTransaction(
                UUID.randomUUID(), TransactionType.ATM_WITHDRAWAL,
                new BigDecimal("1000000"), "KRW", t, customer,
                "sender", null, TransactionChannel.ATM, null));
        var risk = new ExternalRiskSnapshot(atm.getTransactionId(), t, t,
                "PROVIDER_V1", t, ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED, List.of());

        var facts = factsService.snapshot(atm, risk);
        assertThat(facts.baselineStatus()).isEqualTo("UNKNOWN");
        assertThat(facts.baselineCount()).isZero();
        assertThat(facts.recipientStatus()).isEqualTo("UNKNOWN");
        assertThat(facts.exactProviderDeviceMatch()).isFalse();
        assertThat(ScenarioRulePolicy.evaluate(atm, facts, false).riskScore()).isZero();
    }

    @Test
    void expiredLifetimeMockVolumeDoesNotBlockNewTransfer() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.plusSeconds(90);
        String customer = "old-volume-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fds_mock_fact (fact_id, fact_type, external_customer_ref,
                  device_ref, occurred_at, assertion_state, request_fingerprint)
                SELECT md5(random()::text || seq::text)::uuid, 'APP_SECURITY', ?,
                  'old-device', ?::timestamptz - interval '48 hours'
                    - seq * interval '1 minute', 'CLEAR', repeat('a', 64)
                FROM generate_series(1, 1001) AS seq
                """, customer, Timestamp.from(now));
        var current = save(customer, "sender", "recipient", "new-device", t, "1000");
        var risk = new ExternalRiskSnapshot(current.getTransactionId(), t, t,
                "PROVIDER_V1", t, ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED, List.of());
        assertThat(factsService.snapshot(current, risk).appSecurityStatus())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void mockCreationClockCannotBeBackdatedAndBehaviorEvidenceIsImmutable() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID factId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fds_mock_fact (fact_id, fact_type, external_customer_ref,
                    device_ref, occurred_at, assertion_state, request_fingerprint, created_at)
                VALUES (?, 'APP_SECURITY', 'customer', 'device', ?, 'CLEAR', ?, ?)
                """, factId, Timestamp.from(now.minusSeconds(1)), "b".repeat(64),
                Timestamp.from(Instant.EPOCH));
        assertThat(jdbc.queryForObject("SELECT created_at > ? FROM fds_mock_fact WHERE fact_id = ?",
                Boolean.class, Timestamp.from(now.minusSeconds(2)), factId)).isTrue();
        UUID eventId = event(BehaviorEventType.LOGIN, "customer", null, "device",
                null, now.minusSeconds(1));
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE behavior_event SET occurred_at = ? WHERE event_id = ?",
                Timestamp.from(now.minusSeconds(20)), eventId))
                .hasMessageContaining("scenario history is immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM behavior_event WHERE event_id = ?", eventId))
                .hasMessageContaining("scenario history is immutable");
    }

    @Test
    void simultaneousIdenticalMockFactIntakeIsOneInsertAndOneReplay() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.randomUUID();
        MockFactRequest request = new MockFactRequest(id,
                MockFactType.TRANSFER_HISTORY_COMPLETE, "race-" + id,
                null, null, null, null, now, Instant.EPOCH,
                now.plusSeconds(60), "COMPLETE", null, null, null, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); return mocks.ingest(request); });
            var second = pool.submit(() -> { gate.await(); return mocks.ingest(request); });
            gate.countDown();
            var outcomes = List.of(first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(MockFactIntakeService.IntakeResult::created).count())
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fds_mock_fact WHERE fact_id = ?",
                    Integer.class, id)).isEqualTo(1);
            MockFactRequest semanticallyDuplicate = new MockFactRequest(UUID.randomUUID(),
                    request.factType(), request.externalCustomerRef(), null, null, null, null,
                    request.occurredAt(), request.coverageFrom(), request.coverageThrough(),
                    request.assertionState(), null, null, null, null);
            assertThatThrownBy(() -> mocks.ingest(semanticallyDuplicate))
                    .hasMessageContaining("MOCK_FACT_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void appAndRegionMockRequirePriorOwnedDeviceAndEvidenceCannotBeMutated() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String customer = "owner-" + UUID.randomUUID();
        String device = "device-" + UUID.randomUUID();
        UUID id = UUID.randomUUID();
        MockFactRequest app = new MockFactRequest(id, MockFactType.APP_SECURITY,
                customer, null, device, null, null, at, null, null,
                "ALERT", null, null, null, null);
        event(BehaviorEventType.LOGIN, "different-customer", null, device,
                null, at.minusSeconds(1));
        assertThatThrownBy(() -> mocks.ingest(app))
                .hasMessageContaining("INVALID_MOCK_FACT");
        event(BehaviorEventType.LOGIN, customer, null, device,
                null, at.minusSeconds(1));
        mocks.ingest(app);
        assertThat(jdbc.queryForObject(
                "SELECT created_at <= clock_timestamp() AND created_at > ? FROM fds_mock_fact WHERE fact_id = ?",
                Boolean.class, Timestamp.from(at.minusSeconds(2)), id)).isTrue();
        MockFactRequest contradictoryRegion = new MockFactRequest(UUID.randomUUID(),
                MockFactType.NETWORK_REGION, customer, null, device, null, null,
                at, null, null, "OVERSEAS", null, null, null, "KR");
        assertThatThrownBy(() -> mocks.ingest(contradictoryRegion))
                .hasMessageContaining("INVALID_MOCK_FACT");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE fds_mock_fact SET assertion_state = 'CLEAR' WHERE fact_id = ?", id))
                .hasMessageContaining("scenario history is immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM fds_mock_fact WHERE fact_id = ?", id))
                .hasMessageContaining("scenario history is immutable");
        assertThat(jdbc.queryForObject("SELECT assertion_state FROM fds_mock_fact WHERE fact_id = ?",
                String.class, id)).isEqualTo("ALERT");
    }

    @Test
    void derivesRealBaselineFirstRecipientDeviceAndLimitSequenceAsOfCutoff() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t = now.plus(Duration.ofMinutes(2));
        String customer = "scenario-customer";
        String sender = "scenario-sender";
        String recipient = "scenario-first-recipient";
        String device = "scenario-new-device";
        for (int i = 0; i < 10; i++) {
            FinancialTransaction prior = save(customer, sender, "other-recipient-" + i,
                    "old-device", now.minus(Duration.ofDays(3 + i % 3)), "100000");
            jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                    prior.getId());
        }
        FinancialTransaction current = save(customer, sender, recipient, device, t, "1000000");
        UUID deviceEvent = event(BehaviorEventType.DEVICE_REGISTERED, customer, sender,
                device, null, now.minus(Duration.ofMinutes(12)));
        UUID beneficiaryEvent = event(BehaviorEventType.BENEFICIARY_REGISTERED, customer,
                sender, null, recipient, now.minus(Duration.ofMinutes(11)));
        UUID passwordEvent = event(BehaviorEventType.PASSWORD_CHANGED, customer,
                sender, null, null, now.minus(Duration.ofMinutes(10)));
        UUID limitEvent = event(BehaviorEventType.TRANSFER_LIMIT_CHANGED, customer,
                sender, null, null, now.minus(Duration.ofMinutes(5)));
        coverage(MockFactType.TRANSFER_HISTORY_COMPLETE, customer, t);
        coverage(MockFactType.DEVICE_HISTORY_COMPLETE, customer, t);
        mock(new MockFactRequest(UUID.randomUUID(), MockFactType.DEVICE_TRUST, customer,
                sender, device, null, deviceEvent, now.minus(Duration.ofMinutes(12)),
                null, null, "UNVERIFIED", null, null, null, null));
        mock(new MockFactRequest(UUID.randomUUID(), MockFactType.LIMIT_CHANGE, customer,
                sender, null, null, limitEvent, now.minus(Duration.ofMinutes(5)),
                null, null, "SUCCEEDED", "PER_TRANSFER",
                new BigDecimal("500000"), new BigDecimal("6000000"), null));
        var risk = new ExternalRiskSnapshot(current.getTransactionId(), t, t,
                "PROVIDER_V1", t, ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED, List.of());
        var facts = factsService.snapshot(current, risk);
        assertThat(facts.baselineStatus()).isEqualTo("AVAILABLE");
        assertThat(facts.baselineCount()).isEqualTo(10);
        assertThat(facts.baselineDays()).isEqualTo(3);
        assertThat(facts.recipientStatus()).isEqualTo("FIRST");
        assertThat(facts.beneficiaryEventId()).isEqualTo(beneficiaryEvent);
        assertThat(facts.deviceStatus()).isEqualTo("NEW_UNVERIFIED");
        assertThat(facts.passwordEventId()).isEqualTo(passwordEvent);
        assertThat(facts.limitEventId()).isEqualTo(limitEvent);
        assertThat(facts.limitStatus()).isEqualTo("RAISED");
        var score = ScenarioRulePolicy.evaluate(current, facts, false);
        assertThat(score.riskScore()).isEqualTo(90);
        assertThat(score.ruleContributions().get(2).appliedContribution()).isEqualTo(40);
        assertThat(score.ruleContributions().get(8).appliedContribution()).isZero();

        // A Mock fact stored after T is never admitted into the prior Snapshot.
        Instant lateCutoff = now.minus(Duration.ofHours(1));
        FinancialTransaction lateTransaction = save(customer, sender, "late-recipient",
                "late-device", lateCutoff, "1000000");
        var late = factsService.snapshot(lateTransaction,
                new ExternalRiskSnapshot(lateTransaction.getTransactionId(), lateCutoff, lateCutoff,
                        "PROVIDER_V1", lateCutoff, ExternalRiskLookupStatus.SUCCEEDED,
                        ExternalRiskPolicyResult.UNMATCHED, List.of()));
        assertThat(late.baselineStatus()).isEqualTo("UNKNOWN");
    }

    private void coverage(MockFactType type, String customer, Instant t) {
        mock(new MockFactRequest(UUID.randomUUID(), type, customer, null, null,
                null, null, t.minus(Duration.ofMinutes(1)), Instant.EPOCH,
                t, "COMPLETE", null, null, null, null));
    }

    private void mock(MockFactRequest request) { mocks.ingest(request); }

    private UUID event(BehaviorEventType type, String customer, String account,
            String device, String beneficiary, Instant at) {
        UUID id = UUID.randomUUID();
        behaviors.saveAndFlush(new BehaviorEvent(id, type, at, customer,
                account, device, beneficiary, null, "a".repeat(64)));
        return id;
    }

    private FinancialTransaction save(String customer, String sender, String recipient,
            String device, Instant at, String amount) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER, new BigDecimal(amount), "KRW", at,
                customer, sender, recipient, TransactionChannel.MOBILE_BANKING, device));
    }
}
