package com.aifds.backend.persistence;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Scn003RecipientHistoryIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired FinancialTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyEarlierApprovedAndAlreadyCreatedSameCustomerRecipientIsObserved() {
        Instant cutoff = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        UUID eligible = save("customer-a", "recipient-a", cutoff.minus(1, ChronoUnit.MICROS));
        approve(eligible, cutoff);
        UUID atCutoff = save("customer-a", "recipient-a", cutoff);
        approve(atCutoff, cutoff);
        UUID lateIntake = save("customer-a", "recipient-a", cutoff.minusSeconds(2));
        approve(lateIntake, cutoff.plus(1, ChronoUnit.MICROS));
        UUID held = save("customer-a", "recipient-a", cutoff.minusSeconds(3));
        UUID otherCustomer = save("customer-b", "recipient-a", cutoff.minusSeconds(4));
        approve(otherCustomer, cutoff);
        UUID otherRecipient = save("customer-a", "recipient-b", cutoff.minusSeconds(5));
        approve(otherRecipient, cutoff);

        UUID current = UUID.randomUUID();
        assertThat(transactions.findPriorApprovedRecipientTransfers(
                "customer-a", "recipient-a", current, cutoff, PageRequest.of(0, 10)))
                .containsExactly(eligible);
        assertThat(transactions.findPriorApprovedRecipientTransfers(
                "customer-a", "recipient-a", eligible, cutoff, PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(transactions.findPriorApprovedRecipientTransfers(
                "customer-a", "recipient-a", current,
                cutoff.minus(1, ChronoUnit.MICROS), PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(held).isNotEqualTo(eligible);
    }

    private UUID save(String customer, String recipient, Instant occurredAt) {
        UUID id = UUID.randomUUID();
        transactions.saveAndFlush(new FinancialTransaction(id, TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1000"), "KRW", occurredAt, customer, "sender-a",
                recipient, TransactionChannel.MOBILE_BANKING, "device-a"));
        return id;
    }

    private void approve(UUID id, Instant createdAt) {
        jdbc.update("""
                UPDATE financial_transaction
                SET processing_status = 'APPROVED', created_at = ?, updated_at = ?
                WHERE transaction_id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt), id);
    }
}
