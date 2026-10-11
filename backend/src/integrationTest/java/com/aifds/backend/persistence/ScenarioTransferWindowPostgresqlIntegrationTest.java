package com.aifds.backend.persistence;

import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository;
import com.aifds.backend.scenario.mock.MockFactIntakeService;
import com.aifds.backend.scenario.mock.MockFactRequest;
import com.aifds.backend.scenario.mock.MockFactType;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioTransferWindowPostgresqlIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired private ScenarioTransferWindowRepository windows;
    @Autowired private FinancialTransactionRepository transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockFactIntakeService mockFacts;

    @Test
    void outboundUsesCustomerAndSenderAndDistinctRecipients() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(2));
        approve(save("customer-a", "sender-a", "recipient-a", t.minusSeconds(120), "1000000"));
        approve(save("customer-a", "sender-a", "recipient-b", t.minusSeconds(60), "1100000"));
        approve(save("customer-b", "sender-a", "recipient-c", t.minusSeconds(60), "9000000"));
        approve(save("customer-a", "sender-b", "recipient-d", t.minusSeconds(60), "9000000"));
        FinancialTransaction target = save("customer-a", "sender-a", "recipient-c", t, "1200000");
        cover("TRANSFER_HISTORY_COMPLETE", "customer-a", null, t.minus(Duration.ofMinutes(10)), t);

        var result = windows.outbound(target);
        assertThat(result.transferCount()).isEqualTo(3);
        assertThat(result.distinctCount()).isEqualTo(3);
        assertThat(result.totalAmount()).isEqualByComparingTo("3300000");
        assertThat(result.unknownCount()).isZero();
        assertThat(result.available()).isTrue();
    }

    @Test
    void inboundCountsDistinctCustomersAndExcludesOtherRecipients() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(2));
        approve(save("customer-a", "sender-a", "collector", t.minusSeconds(300), "2000000"));
        approve(save("customer-a", "sender-b", "collector", t.minusSeconds(240), "1000000"));
        approve(save("customer-b", "sender-c", "collector", t.minusSeconds(120), "2100000"));
        approve(save("customer-c", "sender-d", "another", t.minusSeconds(60), "9000000"));
        FinancialTransaction target = save("customer-c", "sender-e", "collector", t, "500000");
        cover("INBOUND_ACCOUNT_HISTORY_COMPLETE", "collector-owner", "collector",
                t.minus(Duration.ofHours(24)), t);

        var result = windows.inbound(target);
        assertThat(result.transferCount()).isEqualTo(4);
        assertThat(result.distinctCount()).isEqualTo(3);
        assertThat(result.totalAmount()).isEqualByComparingTo("5600000");
        assertThat(result.available()).isTrue();
        cover("INBOUND_ACCOUNT_HISTORY_COMPLETE", "different-owner", "collector",
                t.minus(Duration.ofHours(24)), t);
        assertThat(windows.inbound(target).available()).isFalse();
    }

    @Test
    void legacyApprovalIsUnknownAndLateCreatedHistoryCannotEnterPastCutoff() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(1));
        jdbc.update("""
                INSERT INTO financial_transaction (transaction_id, transaction_type,
                    amount, currency_code, occurred_at, external_customer_ref,
                    sender_account_ref, recipient_account_ref, channel, processing_status)
                VALUES (?, 'ACCOUNT_TRANSFER', 1000000, 'KRW', ?, 'customer-a',
                    'sender-a', 'recipient-a', 'MOBILE_BANKING', 'APPROVED')
                """, UUID.randomUUID(), Timestamp.from(t.minusSeconds(60)));
        FinancialTransaction target = save("customer-a", "sender-a", "recipient-b", t, "1000000");
        assertThat(windows.outbound(target).unknownCount()).isEqualTo(1);
        assertThat(windows.outbound(target).transferCount()).isEqualTo(1);
        assertThat(windows.outbound(target).available()).isFalse();

        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        FinancialTransaction lateTarget = save("customer-late", "sender-late", "recipient-late",
                past, "1000000");
        approve(save("customer-late", "sender-late", "recipient-other",
                past.minusSeconds(30), "2000000"));
        assertThat(windows.outbound(lateTarget).transferCount()).isEqualTo(1);
    }

    @Test
    void simultaneousAndReverseOccurredRowsAndPostCutoffApprovalCannotEnter() {
        Instant future = Instant.now().truncatedTo(ChronoUnit.MICROS)
                .plus(Duration.ofMinutes(2));
        approve(save("boundary-customer", "boundary-sender", "early",
                future.minusSeconds(1), "1000000"));
        approve(save("boundary-customer", "boundary-sender", "same",
                future, "1000000"));
        approve(save("boundary-customer", "boundary-sender", "future",
                future.plusSeconds(1), "1000000"));
        var current = save("boundary-customer", "boundary-sender", "current",
                future, "1000000");
        assertThat(windows.outbound(current).transferCount()).isEqualTo(2);
        assertThat(windows.outbound(current).distinctCount()).isEqualTo(2);

        Instant past = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        var delayed = save("approval-customer", "approval-sender", "current",
                past, "1000000");
        UUID priorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO financial_transaction (transaction_id, transaction_type,
                    amount, currency_code, occurred_at, created_at,
                    external_customer_ref, sender_account_ref, recipient_account_ref,
                    channel, processing_status)
                VALUES (?, 'ACCOUNT_TRANSFER', 1000000, 'KRW', ?, ?,
                    'approval-customer', 'approval-sender', 'earlier',
                    'MOBILE_BANKING', 'RECEIVED')
                """, priorId, Timestamp.from(past.minusSeconds(10)),
                Timestamp.from(past.minusSeconds(5)));
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' "
                + "WHERE transaction_id = ?", priorId);
        assertThat(windows.outbound(delayed).transferCount()).isEqualTo(1);
        assertThat(windows.outbound(delayed).unknownCount()).isZero();
    }

    private FinancialTransaction save(String customer, String sender, String recipient,
                                      Instant occurredAt, String amount) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER, new BigDecimal(amount), "KRW", occurredAt,
                customer, sender, recipient, TransactionChannel.MOBILE_BANKING, null));
    }

    private void approve(FinancialTransaction tx) {
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                tx.getId());
    }

    private void cover(String type, String owner, String account, Instant from, Instant through) {
        mockFacts.ingest(new MockFactRequest(UUID.randomUUID(), MockFactType.valueOf(type),
                owner, account, null, null, null, through.minusSeconds(30), from,
                through, "COMPLETE", null, null, null, null));
    }
}
