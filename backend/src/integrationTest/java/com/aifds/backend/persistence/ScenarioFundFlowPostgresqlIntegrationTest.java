package com.aifds.backend.persistence;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.scenario.history.ScenarioFundFlowRepository;
import com.aifds.backend.scenario.mock.MockFactIntakeService;
import com.aifds.backend.scenario.mock.MockFactRequest;
import com.aifds.backend.scenario.mock.MockFactType;
import com.aifds.backend.scenario.mock.MockFundLinkIntakeService;
import com.aifds.backend.scenario.mock.MockFundLinkRequest;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
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
class ScenarioFundFlowPostgresqlIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired ScenarioFundFlowRepository flows;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired BehaviorEventRepository behaviors;
    @Autowired MockFundLinkIntakeService links;
    @Autowired MockFactIntakeService mockFacts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void atmRequiresProvenIncomingSourceAndEightyPercentUse() {
        Instant t = future();
        FinancialTransaction source = save(TransactionType.ACCOUNT_TRANSFER,
                "payer", "payer-account", "owner-account", "10000000",
                t.minus(Duration.ofMinutes(20)));
        approve(source);
        FinancialTransaction unrelated = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "another-account", null, "9000000", t.minusSeconds(60));
        approve(unrelated);
        FinancialTransaction target = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "8000000", t);
        assertThat(flows.snapshot(target).atm().status()).isEqualTo("UNKNOWN");
        coverage("owner", "owner-account", t.minus(Duration.ofMinutes(30)), t);
        MockFundLinkRequest assertion = link("SCN006", source, target,
                "owner", "owner-account", "VERIFIED");
        assertThat(links.ingest(assertion)).isTrue();
        assertThat(links.ingest(assertion)).isFalse();
        var fact = flows.snapshot(target).atm();
        assertThat(fact.hit()).isTrue();
        assertThat(fact.ratioBasisPoints()).isEqualTo(8000);
        assertThat(fact.sourceTransactionId()).isEqualTo(source.getTransactionId());
        assertThat(fact.linkedPriorTransactionIds()).isEmpty();
    }

    @Test
    void loanNeedsNewBeneficiaryAndFailsClosedForPartialMock() {
        Instant t = future();
        FinancialTransaction source = save(TransactionType.LOAN_DISBURSED,
                "owner", "loan-account", null, "10000000",
                t.minus(Duration.ofMinutes(30)));
        approve(source);
        FinancialTransaction target = save(TransactionType.ACCOUNT_TRANSFER,
                "owner", "loan-account", "new-recipient", "8000000", t);
        coverage("owner", "loan-account", t.minus(Duration.ofMinutes(60)), t);
        MockFundLinkRequest partial = link("SCN007", source, target,
                "owner", "loan-account", "PARTIAL");
        links.ingest(partial);
        assertThat(flows.snapshot(target).loan().reasonCode())
                .isEqualTo("MOCK_PARTIAL_OR_FAILED");
        // A second, conflicting declaration cannot silently replace the partial result.
        assertThatThrownBy(() -> links.ingest(link("SCN007", source, target,
                "owner", "loan-account", "VERIFIED")))
                .isInstanceOf(com.aifds.backend.scenario.mock.MockFactException.class);
        assertThat(flows.snapshot(target).loan().reasonCode())
                .isEqualTo("MOCK_PARTIAL_OR_FAILED");
    }

    @Test
    void loanVerifiedLinkUsesRegisteredNewBeneficiary() {
        Instant t = future();
        FinancialTransaction source = save(TransactionType.LOAN_DISBURSED,
                "owner", "loan-account", null, "10000000",
                t.minus(Duration.ofMinutes(30)));
        approve(source);
        FinancialTransaction target = save(TransactionType.ACCOUNT_TRANSFER,
                "owner", "loan-account", "new-recipient", "8000000", t);
        coverage("owner", "loan-account", t.minus(Duration.ofMinutes(60)), t);
        behaviors.saveAndFlush(new BehaviorEvent(UUID.randomUUID(),
                BehaviorEventType.BENEFICIARY_REGISTERED, t.minus(Duration.ofMinutes(20)),
                "owner", "loan-account", null, "new-recipient", null,
                "0".repeat(64)));
        links.ingest(link("SCN007", source, target,
                "owner", "loan-account", "VERIFIED"));
        var fact = flows.snapshot(target).loan();
        assertThat(fact.hit()).isTrue();
        assertThat(fact.beneficiaryEventId()).isNotNull();
        assertThat(fact.ratioBasisPoints()).isEqualTo(8000);
    }

    @Test
    void twoSourcesAndUnrelatedSameAccountUseNeverImplyAttribution() {
        Instant t = future();
        FinancialTransaction first = save(TransactionType.ACCOUNT_TRANSFER,
                "payer-a", "payer-a-account", "owner-account", "10000000",
                t.minus(Duration.ofMinutes(20)));
        FinancialTransaction second = save(TransactionType.ACCOUNT_TRANSFER,
                "payer-b", "payer-b-account", "owner-account", "10000000",
                t.minus(Duration.ofMinutes(10)));
        approve(first);
        approve(second);
        FinancialTransaction unrelated = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "2000000", t.minusSeconds(40));
        approve(unrelated);
        FinancialTransaction target = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "8000000", t);
        coverage("owner", "owner-account", t.minus(Duration.ofMinutes(30)), t);
        assertThat(flows.snapshot(target).atm().reasonCode()).isEqualTo("SOURCE_LINK_MISSING");
        links.ingest(link("SCN006", first, target, "owner", "owner-account", "VERIFIED"));
        var attributed = flows.snapshot(target).atm();
        assertThat(attributed.hit()).isTrue();
        assertThat(attributed.sourceTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(attributed.linkedPriorTransactionIds()).doesNotContain(unrelated.getTransactionId());
    }

    @Test
    void reverseOrderAndLateApprovalAreUnknownAtOriginalCutoff() throws Exception {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(2);
        FinancialTransaction reverseSource = save(TransactionType.ACCOUNT_TRANSFER,
                "payer", "payer-account", "owner-account", "10000000", t.plusSeconds(1));
        approve(reverseSource);
        FinancialTransaction target = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "8000000", t);
        coverage("owner", "owner-account", t.minus(Duration.ofMinutes(30)), t);
        links.ingest(link("SCN006", reverseSource, target,
                "owner", "owner-account", "VERIFIED"));
        assertThat(flows.snapshot(target).atm().reasonCode())
                .isEqualTo("SOURCE_NOT_APPROVED_AS_OF_CUTOFF");

        FinancialTransaction lateSource = save(TransactionType.ACCOUNT_TRANSFER,
                "payer", "payer-account", "second-owner", "10000000",
                t.minusSeconds(20));
        FinancialTransaction lateTarget = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "second-owner", null, "8000000", t);
        coverage("owner", "second-owner", t.minus(Duration.ofMinutes(30)), t);
        links.ingest(link("SCN006", lateSource, lateTarget,
                "owner", "second-owner", "VERIFIED"));
        long pause = Duration.between(Instant.now(), t.plusMillis(100)).toMillis();
        if (pause > 0) Thread.sleep(pause);
        approve(lateSource);
        assertThat(flows.snapshot(lateTarget).atm().reasonCode())
                .isEqualTo("SOURCE_NOT_APPROVED_AS_OF_CUTOFF");
    }

    @Test
    void onlyVerifiedApprovedPriorUsesContributeToSourceRatio() {
        Instant t = future();
        FinancialTransaction source = save(TransactionType.ACCOUNT_TRANSFER,
                "payer", "payer-account", "owner-account", "10000000",
                t.minus(Duration.ofMinutes(20)));
        approve(source);
        FinancialTransaction prior = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "4000000", t.minusSeconds(40));
        approve(prior);
        FinancialTransaction unlinked = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "5000000", t.minusSeconds(30));
        approve(unlinked);
        FinancialTransaction current = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "4000000", t);
        coverage("owner", "owner-account", t.minus(Duration.ofMinutes(30)), t);
        links.ingest(link("SCN006", source, prior, "owner", "owner-account", "VERIFIED"));
        links.ingest(link("SCN006", source, current, "owner", "owner-account", "VERIFIED"));
        var flow = flows.snapshot(current).atm();
        assertThat(flow.hit()).isTrue();
        assertThat(flow.ratioBasisPoints()).isEqualTo(8000);
        assertThat(flow.linkedPriorTransactionIds()).containsExactly(prior.getTransactionId());
        assertThat(flow.linkedPriorTransactionIds()).doesNotContain(unlinked.getTransactionId());
    }

    @Test
    void lateMockReceiptAndCompetingSourceLinksFailClosed() throws Exception {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(2);
        FinancialTransaction first = save(TransactionType.ACCOUNT_TRANSFER,
                "payer-a", "payer-a-account", "owner-account", "10000000",
                t.minusSeconds(20));
        FinancialTransaction second = save(TransactionType.ACCOUNT_TRANSFER,
                "payer-b", "payer-b-account", "owner-account", "10000000",
                t.minusSeconds(10));
        approve(first);
        approve(second);
        FinancialTransaction target = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "8000000", t);
        coverage("owner", "owner-account", t.minus(Duration.ofMinutes(30)), t);
        links.ingest(link("SCN006", first, target, "owner", "owner-account", "VERIFIED"));
        links.ingest(link("SCN006", second, target, "owner", "owner-account", "VERIFIED"));
        assertThat(flows.snapshot(target).atm().reasonCode()).isEqualTo("SOURCE_LINK_CONFLICT");

        FinancialTransaction lateTarget = save(TransactionType.ATM_WITHDRAWAL,
                "owner", "owner-account", null, "8000000", t);
        long pause = Duration.between(Instant.now(), t.plusMillis(100)).toMillis();
        if (pause > 0) Thread.sleep(pause);
        links.ingest(new MockFundLinkRequest(UUID.randomUUID(), "SCN006",
                first.getTransactionId(), lateTarget.getTransactionId(),
                "owner", "owner-account", "VERIFIED", t.minusSeconds(1)));
        assertThat(flows.snapshot(lateTarget).atm().reasonCode())
                .isEqualTo("SOURCE_LINK_MISSING");
    }

    private Instant future() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(2));
    }

    private FinancialTransaction save(TransactionType type, String customer,
            String sender, String recipient, String amount, Instant occurredAt) {
        TransactionChannel channel = switch (type) {
            case ATM_WITHDRAWAL -> TransactionChannel.ATM;
            case LOAN_DISBURSED -> TransactionChannel.CORE_BANKING;
            case ACCOUNT_TRANSFER -> TransactionChannel.MOBILE_BANKING;
            case OPEN_BANKING_TRANSFER -> TransactionChannel.OPEN_BANKING;
        };
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(), type,
                new BigDecimal(amount), "KRW", occurredAt, customer,
                sender, recipient, channel, null));
    }

    private void approve(FinancialTransaction tx) {
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                tx.getId());
    }

    private void coverage(String customer, String account, Instant from, Instant through) {
        mockFacts.ingest(new MockFactRequest(UUID.randomUUID(),
                MockFactType.ACCOUNT_FLOW_HISTORY_COMPLETE, customer, account,
                null, null, null, through.minusSeconds(5), from, through,
                "COMPLETE", null, null, null, null));
    }

    private MockFundLinkRequest link(String scenario, FinancialTransaction source,
            FinancialTransaction target, String customer, String account, String state) {
        return new MockFundLinkRequest(UUID.randomUUID(), scenario,
                source.getTransactionId(), target.getTransactionId(), customer,
                account, state, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
