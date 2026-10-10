package com.aifds.backend.scenario.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Read-only as-of aggregates. Legacy rows without approved_at stay UNKNOWN. */
@Repository
public class ScenarioHistoryRepository {
    private final JdbcTemplate jdbc;

    public ScenarioHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public TransferAggregate customerBaseline(String customerRef, Instant from, Instant cutoff) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE approved_at IS NULL) AS unknown_count,
                       COUNT(*) FILTER (WHERE approved_at <= ?) AS eligible_count,
                       COUNT(DISTINCT (occurred_at AT TIME ZONE 'Asia/Seoul')::date)
                           FILTER (WHERE approved_at <= ?) AS eligible_days,
                       COALESCE(SUM(amount) FILTER (WHERE approved_at <= ?), 0) AS eligible_sum
                FROM financial_transaction
                WHERE external_customer_ref = ?
                  AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER')
                  AND processing_status = 'APPROVED'
                  AND occurred_at >= ? AND occurred_at < ? AND created_at <= ?
                """, (rs, row) -> new TransferAggregate(
                        rs.getLong("unknown_count"), rs.getLong("eligible_count"),
                        rs.getLong("eligible_days"), rs.getBigDecimal("eligible_sum")),
                Timestamp.from(cutoff), Timestamp.from(cutoff), Timestamp.from(cutoff),
                customerRef, Timestamp.from(from), Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    public RecipientAggregate priorRecipient(String customerRef, String recipientRef,
                                             Instant cutoff) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE approved_at IS NULL) AS unknown_count,
                       COUNT(*) FILTER (WHERE approved_at <= ?) AS eligible_count
                FROM financial_transaction
                WHERE external_customer_ref = ? AND recipient_account_ref = ?
                  AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER')
                  AND processing_status = 'APPROVED'
                  AND occurred_at < ? AND created_at <= ?
                """, (rs, row) -> new RecipientAggregate(
                        rs.getLong("unknown_count"), rs.getLong("eligible_count")),
                Timestamp.from(cutoff), customerRef, recipientRef,
                Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    public TransferAggregate dailyUsed(String customerRef, String accountRef,
                                       Instant dayStart, Instant cutoff) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE approved_at IS NULL) AS unknown_count,
                       COUNT(*) FILTER (WHERE approved_at <= ?) AS eligible_count,
                       0 AS eligible_days,
                       COALESCE(SUM(amount) FILTER (WHERE approved_at <= ?), 0) AS eligible_sum
                FROM financial_transaction
                WHERE external_customer_ref = ? AND sender_account_ref = ?
                  AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER')
                  AND processing_status = 'APPROVED'
                  AND occurred_at >= ? AND occurred_at < ? AND created_at <= ?
                """, (rs, row) -> new TransferAggregate(
                        rs.getLong("unknown_count"), rs.getLong("eligible_count"),
                        rs.getLong("eligible_days"), rs.getBigDecimal("eligible_sum")),
                Timestamp.from(cutoff), Timestamp.from(cutoff), customerRef, accountRef,
                Timestamp.from(dayStart), Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    public DeviceUse firstDeviceUse(String customerRef, String deviceRef,
                                    Instant from, Instant cutoff) {
        return jdbc.query("""
                SELECT event_id, occurred_at FROM behavior_event
                WHERE external_customer_ref = ? AND device_ref = ?
                  AND event_type IN ('LOGIN', 'DEVICE_REGISTERED')
                  AND occurred_at >= ? AND occurred_at <= ? AND created_at <= ?
                ORDER BY occurred_at ASC, event_id ASC LIMIT 1
                """, rs -> rs.next() ? new DeviceUse(
                        rs.getObject("event_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()) : null,
                customerRef, deviceRef, Timestamp.from(from),
                Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    public record TransferAggregate(long unknownCount, long eligibleCount,
                                    long eligibleDays, BigDecimal eligibleSum) { }
    public record RecipientAggregate(long unknownCount, long eligibleCount) { }
    public record DeviceUse(UUID eventId, Instant occurredAt) { }
}
