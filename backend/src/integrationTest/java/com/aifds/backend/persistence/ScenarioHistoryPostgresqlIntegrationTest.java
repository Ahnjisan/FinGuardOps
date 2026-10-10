package com.aifds.backend.persistence;

import com.aifds.backend.scenario.history.ScenarioHistoryRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioHistoryPostgresqlIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired private FinancialTransactionRepository transactions;
    @Autowired private ScenarioHistoryRepository history;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void approvalTimestampProvesAsOfStateAndLegacyRowsRemainUnknown() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(Duration.ofMinutes(1));
        FinancialTransaction proven = save("customer-a", "sender-a", "recipient-a",
                now.minus(Duration.ofDays(2)), "100000");
        jdbc.update("UPDATE financial_transaction SET processing_status = 'APPROVED' WHERE id = ?",
                proven.getId());
        Timestamp approvedTimestamp = jdbc.queryForObject(
                "SELECT approved_at FROM financial_transaction WHERE id = ?",
                Timestamp.class, proven.getId());
        Instant approvedAt = approvedTimestamp == null ? null : approvedTimestamp.toInstant();
        assertThat(approvedAt).isNotNull().isBefore(cutoff);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE financial_transaction SET approved_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(2))), proven.getId()))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO financial_transaction (transaction_id, transaction_type,
                    amount, currency_code, occurred_at, external_customer_ref,
                    sender_account_ref, recipient_account_ref, channel,
                    processing_status, approved_at)
                VALUES (?, 'ACCOUNT_TRANSFER', 100000, 'KRW', ?, 'customer-a',
                    'sender-a', 'recipient-a', 'MOBILE_BANKING', 'APPROVED', ?)
                """, UUID.randomUUID(), Timestamp.from(now.minus(Duration.ofDays(3))),
                Timestamp.from(now.minus(Duration.ofDays(3)))))
                .hasMessageContaining("approved_at is database owned");

        jdbc.queryForObject("""
                INSERT INTO financial_transaction (transaction_id, transaction_type,
                    amount, currency_code, occurred_at, external_customer_ref,
                    sender_account_ref, recipient_account_ref, channel, device_ref,
                    processing_status)
                VALUES (?, 'ACCOUNT_TRANSFER', 200000, 'KRW', ?, 'customer-a',
                    'sender-a', 'recipient-a', 'MOBILE_BANKING', 'device-a', 'APPROVED')
                RETURNING id
                """, Long.class, UUID.randomUUID(),
                Timestamp.from(now.minus(Duration.ofDays(3))));
        var baseline = history.customerBaseline("customer-a",
                cutoff.minus(Duration.ofDays(90)), cutoff);
        assertThat(baseline.eligibleCount()).isEqualTo(1);
        assertThat(baseline.unknownCount()).isEqualTo(1);
        var recipient = history.priorRecipient("customer-a", "recipient-a", cutoff);
        assertThat(recipient.eligibleCount()).isEqualTo(1);
        assertThat(recipient.unknownCount()).isEqualTo(1);
        var daily = history.dailyUsed("customer-a", "sender-a",
                cutoff.minus(Duration.ofDays(4)), cutoff);
        assertThat(daily.eligibleSum()).isEqualByComparingTo("100000");
        assertThat(daily.unknownCount()).isEqualTo(1);
        assertThat(history.priorRecipient("customer-b", "recipient-a", cutoff)
                .eligibleCount()).isZero();
        // A late-created historical row cannot enter an earlier cutoff.
        assertThat(history.customerBaseline("customer-a",
                now.minus(Duration.ofDays(90)), now.minus(Duration.ofHours(1)))
                .eligibleCount()).isZero();
    }

    private FinancialTransaction save(String customer, String sender, String recipient,
            Instant occurredAt, String amount) {
        return transactions.saveAndFlush(new FinancialTransaction(UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER, new BigDecimal(amount), "KRW",
                occurredAt, customer, sender, recipient,
                TransactionChannel.MOBILE_BANKING, "device-a"));
    }
}
