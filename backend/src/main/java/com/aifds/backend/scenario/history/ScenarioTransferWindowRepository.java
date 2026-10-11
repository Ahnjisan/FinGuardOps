package com.aifds.backend.scenario.history;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Cutoff-fixed transfer windows. The evaluated request is included exactly once. */
@Repository
public class ScenarioTransferWindowRepository {
    private final JdbcTemplate jdbc;

    public ScenarioTransferWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Window outbound(FinancialTransaction transaction) {
        return query(transaction, Duration.ofMinutes(10), true);
    }

    public Window inbound(FinancialTransaction transaction) {
        return query(transaction, Duration.ofHours(24), false);
    }

    private Window query(FinancialTransaction transaction, Duration window, boolean outbound) {
        FinancialTransaction tx = Objects.requireNonNull(transaction);
        if (tx.getTransactionType() != TransactionType.ACCOUNT_TRANSFER
                && tx.getTransactionType() != TransactionType.OPEN_BANKING_TRANSFER) {
            throw new IllegalArgumentException("Transfer window requires a transfer");
        }
        if (tx.getRecipientAccountRef() == null || !"KRW".equals(tx.getCurrencyCode())) {
            throw new IllegalArgumentException("Transfer window requires a KRW recipient");
        }
        Instant cutoff = tx.getOccurredAt();
        String keyPredicate = outbound
                ? "external_customer_ref = ? AND sender_account_ref = ?"
                : "recipient_account_ref = ?";
        String distinctRef = outbound ? "recipient_account_ref" : "external_customer_ref";
        String sql = """
                WITH history AS (
                    SELECT transaction_id, amount, %s AS distinct_ref, approved_at
                    FROM financial_transaction
                    WHERE %s
                      AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER')
                      AND currency_code = 'KRW'
                      AND processing_status = 'APPROVED'
                      AND occurred_at >= ? AND occurred_at < ?
                      AND created_at <= ? AND transaction_id <> ?
                ), eligible AS (
                    SELECT amount, distinct_ref FROM history WHERE approved_at <= ?
                    UNION ALL SELECT ?::numeric, ?::varchar
                )
                SELECT (SELECT COUNT(*) FROM eligible) AS transfer_count,
                       (SELECT COUNT(DISTINCT distinct_ref) FROM eligible) AS distinct_count,
                       (SELECT COALESCE(SUM(amount), 0) FROM eligible) AS total_amount,
                       (SELECT COUNT(*) FROM history WHERE approved_at IS NULL) AS unknown_count
                """.formatted(distinctRef, keyPredicate);
        Object[] params = outbound
                ? new Object[] { tx.getExternalCustomerRef(), tx.getSenderAccountRef(),
                        Timestamp.from(cutoff.minus(window)), Timestamp.from(cutoff),
                        Timestamp.from(cutoff), tx.getTransactionId(), Timestamp.from(cutoff),
                        tx.getAmount(), tx.getRecipientAccountRef() }
                : new Object[] { tx.getRecipientAccountRef(),
                        Timestamp.from(cutoff.minus(window)), Timestamp.from(cutoff),
                        Timestamp.from(cutoff), tx.getTransactionId(), Timestamp.from(cutoff),
                        tx.getAmount(), tx.getExternalCustomerRef() };
        Window observed = jdbc.queryForObject(sql, (rs, row) -> new Window(
                rs.getLong("transfer_count"), rs.getLong("distinct_count"),
                rs.getBigDecimal("total_amount"), rs.getLong("unknown_count"), null), params);
        UUID proof = coverageProof(tx, cutoff.minus(window), cutoff, outbound);
        return new Window(observed.transferCount(), observed.distinctCount(),
                observed.totalAmount(), observed.unknownCount(), proof);
    }

    private UUID coverageProof(FinancialTransaction tx, Instant from, Instant cutoff,
                               boolean outbound) {
        String predicate = outbound ? "external_customer_ref = ? AND account_ref IS NULL"
                : "account_ref = ?";
        String type = outbound ? "TRANSFER_HISTORY_COMPLETE"
                : "INBOUND_ACCOUNT_HISTORY_COMPLETE";
        String subject = outbound ? tx.getExternalCustomerRef() : tx.getRecipientAccountRef();
        return jdbc.query("""
                WITH proofs AS (
                    SELECT fact_id, external_customer_ref, occurred_at
                    FROM fds_mock_fact
                    WHERE fact_type = ? AND %s AND assertion_state = 'COMPLETE'
                      AND coverage_from <= ? AND coverage_through >= ?
                      AND occurred_at <= ? AND created_at <= ?
                )
                SELECT fact_id FROM proofs
                WHERE (SELECT COUNT(DISTINCT external_customer_ref) FROM proofs) = 1
                ORDER BY occurred_at DESC, fact_id ASC LIMIT 1
                """.formatted(predicate), rs -> rs.next()
                        ? rs.getObject("fact_id", UUID.class) : null,
                type, subject, Timestamp.from(from), Timestamp.from(cutoff),
                Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    public record Window(long transferCount, long distinctCount,
                         @JsonSerialize(using = ToStringSerializer.class)
                         BigDecimal totalAmount, long unknownCount, UUID coverageFactId) {
        public boolean available() {
            return coverageFactId != null && unknownCount == 0;
        }
    }
}
