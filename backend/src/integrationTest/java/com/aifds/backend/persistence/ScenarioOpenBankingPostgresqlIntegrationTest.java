package com.aifds.backend.persistence;

import com.aifds.backend.scenario.history.ScenarioOpenBankingRepository;
import com.aifds.backend.scenario.mock.*;
import com.aifds.backend.transaction.entity.*;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioOpenBankingPostgresqlIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired ScenarioOpenBankingRepository chains;
    @Autowired MockOpenAccountIntakeService ownership;
    @Autowired MockFundLinkIntakeService links;
    @Autowired MockFactIntakeService facts;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentDuplicateOwnershipReceiptHasOneImmutableAssertion() throws Exception {
        var assertion = new MockOpenAccountRequest(UUID.randomUUID(), "owner", "external-concurrent",
                "VERIFIED", "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> { gate.await(); return ownership.ingest(assertion); });
            var second = pool.submit(() -> { gate.await(); return ownership.ingest(assertion); });
            gate.countDown();
            assertThat(java.util.List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM fds_mock_open_account WHERE assertion_id = ?
                    """, Long.class, assertion.assertionId())).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoVerifiedExternalAccountsAndDirectionallyLinkedResendAreAvailable() throws Exception {
        Instant t = future();
        owner("external-a", "VERIFIED");
        owner("external-b", "VERIFIED");
        Thread.sleep(100);
        Instant sourceAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusMillis(10);
        FinancialTransaction a = source("external-a", "hub", "3000000", sourceAt);
        FinancialTransaction b = source("external-b", "hub", "7000000", sourceAt.plusMillis(1));
        approve(a);
        approve(b);
        FinancialTransaction unrelated = source("external-c", "hub", "9000000",
                sourceAt.plusMillis(2));
        approve(unrelated);
        FinancialTransaction target = resend("hub", "outbound", "8000000", t);
        link(a, target);
        link(b, target);
        assertThat(chains.snapshot(target).reasonCode())
                .isEqualTo("ACCOUNT_HISTORY_INCOMPLETE");
        cover(t);
        var chain = chains.snapshot(target);
        assertThat(chain.hit()).isTrue();
        assertThat(chain.concentrationAmount()).isEqualByComparingTo("10000000");
        assertThat(chain.ratioBasisPoints()).isEqualTo(8000);
        assertThat(chain.stages()).extracting(stage -> stage.sourceTransactionId())
                .containsExactly(a.getTransactionId(), b.getTransactionId());
        assertThat(chain.stages()).extracting(stage -> stage.sourceTransactionId())
                .doesNotContain(unrelated.getTransactionId());
        FinancialTransaction competing = resend("hub", "competing", "3000000",
                t.plusSeconds(1));
        link(a, competing);
        assertThat(chains.snapshot(competing).reasonCode())
                .isEqualTo("SOURCE_REUSED_ACROSS_TARGETS");
        FinancialTransaction late = resend("hub", "late", "3000000", t);
        links.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN008",
                b.getTransactionId(), late.getTransactionId(), "owner", "hub",
                "VERIFIED", t.plusSeconds(1)));
        assertThat(chains.snapshot(late).reasonCode()).isEqualTo("SOURCE_LINK_MISSING");
        FinancialTransaction reversedSource = source("external-d", "hub", "3000000",
                t.plusSeconds(2));
        approve(reversedSource);
        FinancialTransaction reversedTarget = resend("hub", "reversed", "3000000", t);
        link(reversedSource, reversedTarget);
        assertThat(chains.snapshot(reversedTarget).reasonCode())
                .isEqualTo("SOURCE_NOT_APPROVED_AS_OF_CUTOFF");
        FinancialTransaction partialTarget = resend("hub", "partial", "3000000", t);
        FinancialTransaction partialSource = source("external-e", "hub", "3000000",
                Instant.now().truncatedTo(ChronoUnit.MICROS).minusMillis(10));
        approve(partialSource);
        links.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN008",
                partialSource.getTransactionId(), partialTarget.getTransactionId(),
                "owner", "hub", "PARTIAL", Instant.now().truncatedTo(ChronoUnit.MICROS)));
        assertThat(chains.snapshot(partialTarget).reasonCode())
                .isEqualTo("MOCK_PARTIAL_OR_FAILED");
        links.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN007",
                a.getTransactionId(), target.getTransactionId(), "owner", "hub",
                "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS)));
        assertThat(chains.snapshot(target).reasonCode())
                .isEqualTo("COMPETING_SOURCE_FLOW");
    }

    @Test
    void partialOwnershipNormalConsolidationAndDuplicateReceiptDoNotInventRisk() throws Exception {
        Instant t = future();
        var assertion = new MockOpenAccountRequest(UUID.randomUUID(), "owner", "external-a",
                "VERIFIED", "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertThat(ownership.ingest(assertion)).isTrue();
        owner("external-b", "PARTIAL");
        owner("external-c", "VERIFIED");
        Thread.sleep(100);
        Instant sourceAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusMillis(10);
        FinancialTransaction a = source("external-a", "hub", "3000000", sourceAt);
        FinancialTransaction b = source("external-b", "hub", "7000000", sourceAt.plusMillis(1));
        approve(a);
        approve(b);
        FinancialTransaction target = resend("hub", "outbound", "3000000", t);
        assertThat(ownership.ingest(assertion)).isFalse();
        assertThatThrownBy(() -> ownership.ingest(new MockOpenAccountRequest(
                assertion.assertionId(), "owner", "external-a", "PARTIAL", "VERIFIED",
                assertion.occurredAt()))).isInstanceOf(MockFactException.class);
        cover(t);
        link(a, target);
        link(b, target);
        assertThat(chains.snapshot(target).reasonCode())
                .isEqualTo("EXTERNAL_OWNERSHIP_UNPROVEN");
        // Another target with full evidence but a routine partial resend stays below the rule.
        FinancialTransaction normal = resend("hub", "other-outbound", "3000000",
                t.plusSeconds(1));
        FinancialTransaction c = source("external-c", "hub", "7000000", sourceAt.plusMillis(2));
        FinancialTransaction a2 = source("external-a", "hub", "3000000", sourceAt.plusMillis(3));
        approve(c);
        approve(a2);
        link(a2, normal);
        link(c, normal);
        var chain = chains.snapshot(normal);
        assertThat(chain.status()).isEqualTo("AVAILABLE");
        assertThat(chain.ratioBasisPoints()).isEqualTo(3000);
        assertThat(chain.hit()).isFalse();
    }

    private Instant future() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(3));
    }

    private FinancialTransaction source(String external, String hub, String amount, Instant at) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(),
                TransactionType.OPEN_BANKING_TRANSFER, new BigDecimal(amount), "KRW", at,
                "owner", external, hub, TransactionChannel.OPEN_BANKING, null));
    }

    private FinancialTransaction resend(String hub, String recipient, String amount, Instant at) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER, new BigDecimal(amount), "KRW", at,
                "owner", hub, recipient, TransactionChannel.MOBILE_BANKING, null));
    }

    private void approve(FinancialTransaction tx) {
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                tx.getId());
    }

    private void owner(String account, String state) {
        ownership.ingest(new MockOpenAccountRequest(UUID.randomUUID(), "owner", account,
                state, state, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private void cover(Instant t) {
        facts.ingest(new MockFactRequest(UUID.randomUUID(),
                MockFactType.ACCOUNT_FLOW_HISTORY_COMPLETE, "owner", "hub",
                null, null, null, t.minusSeconds(5), t.minus(Duration.ofMinutes(35)),
                t.plusSeconds(1), "COMPLETE", null, null, null, null));
    }

    private void link(FinancialTransaction source, FinancialTransaction target) {
        links.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN008",
                source.getTransactionId(), target.getTransactionId(), "owner", "hub",
                "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }
}
