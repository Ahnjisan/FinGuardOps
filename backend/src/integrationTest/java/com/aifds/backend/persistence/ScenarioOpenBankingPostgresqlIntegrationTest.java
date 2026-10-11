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
    void twoVerifiedExternalAccountsAndDirectionallyLinkedResendAreAvailable() {
        Instant t = future();
        FinancialTransaction a = source("external-a", "hub", "3000000", t.minusSeconds(120));
        FinancialTransaction b = source("external-b", "hub", "7000000", t.minusSeconds(60));
        approve(a);
        approve(b);
        FinancialTransaction unrelated = source("external-c", "hub", "9000000",
                t.minusSeconds(30));
        approve(unrelated);
        FinancialTransaction target = resend("hub", "outbound", "8000000", t);
        owner("external-a", "VERIFIED");
        owner("external-b", "VERIFIED");
        cover(t);
        link(a, target);
        link(b, target);
        var chain = chains.snapshot(target);
        assertThat(chain.hit()).isTrue();
        assertThat(chain.concentrationAmount()).isEqualByComparingTo("10000000");
        assertThat(chain.ratioBasisPoints()).isEqualTo(8000);
        assertThat(chain.stages()).extracting(stage -> stage.sourceTransactionId())
                .containsExactly(a.getTransactionId(), b.getTransactionId());
        assertThat(chain.stages()).extracting(stage -> stage.sourceTransactionId())
                .doesNotContain(unrelated.getTransactionId());
    }

    @Test
    void partialOwnershipNormalConsolidationAndDuplicateReceiptDoNotInventRisk() {
        Instant t = future();
        FinancialTransaction a = source("external-a", "hub", "3000000", t.minusSeconds(120));
        FinancialTransaction b = source("external-b", "hub", "7000000", t.minusSeconds(60));
        approve(a);
        approve(b);
        FinancialTransaction target = resend("hub", "outbound", "3000000", t);
        var assertion = new MockOpenAccountRequest(UUID.randomUUID(), "owner", "external-a",
                "VERIFIED", "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertThat(ownership.ingest(assertion)).isTrue();
        assertThat(ownership.ingest(assertion)).isFalse();
        assertThatThrownBy(() -> ownership.ingest(new MockOpenAccountRequest(
                assertion.assertionId(), "owner", "external-a", "PARTIAL", "VERIFIED",
                assertion.occurredAt()))).isInstanceOf(MockFactException.class);
        owner("external-b", "PARTIAL");
        cover(t);
        link(a, target);
        link(b, target);
        assertThat(chains.snapshot(target).reasonCode())
                .isEqualTo("EXTERNAL_OWNERSHIP_UNPROVEN");
        // Another target with full evidence but a routine partial resend stays below the rule.
        FinancialTransaction normal = resend("hub", "other-outbound", "3000000",
                t.plusSeconds(1));
        FinancialTransaction c = source("external-c", "hub", "7000000", t.minusSeconds(50));
        approve(c);
        owner("external-c", "VERIFIED");
        link(a, normal);
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
                null, null, null, t.minusSeconds(5), t.minus(Duration.ofMinutes(31)),
                t.plusSeconds(1), "COMPLETE", null, null, null, null));
    }

    private void link(FinancialTransaction source, FinancialTransaction target) {
        links.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN008",
                source.getTransactionId(), target.getTransactionId(), "owner", "hub",
                "VERIFIED", Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }
}
