package com.aifds.backend.scenario.history;

import com.aifds.backend.scenario.ScenarioFundFlowFacts;
import com.aifds.backend.scenario.ScenarioFundFlowFacts.Flow;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Cutoff fixed, fail-closed local Mock funding assertions. */
@Repository
public class ScenarioFundFlowRepository {
    private final JdbcTemplate jdbc;

    public ScenarioFundFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ScenarioFundFlowFacts snapshot(FinancialTransaction target) {
        Flow atm = target.getTransactionType() == TransactionType.ATM_WITHDRAWAL
                ? flow(target, "SCN006", Duration.ofMinutes(30))
                : Flow.unknown("NOT_ATM_WITHDRAWAL");
        Flow loan = target.getTransactionType() == TransactionType.ACCOUNT_TRANSFER
                || target.getTransactionType() == TransactionType.OPEN_BANKING_TRANSFER
                ? flow(target, "SCN007", Duration.ofMinutes(60))
                : Flow.unknown("NOT_OUTBOUND_TRANSFER");
        return new ScenarioFundFlowFacts("SCN006-007-contract-v1", atm, loan);
    }

    private Flow flow(FinancialTransaction target, String scenario, Duration window) {
        Instant cutoff = target.getOccurredAt();
        List<Link> links = jdbc.query("""
                SELECT link_id, source_transaction_id, external_customer_ref,
                    account_ref, assertion_state FROM fds_mock_fund_link
                WHERE scenario_code = ? AND target_transaction_id = ?
                  AND occurred_at <= ? AND created_at <= ?
                ORDER BY link_id ASC LIMIT 2
                """, (rs, row) -> new Link(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5)), scenario, target.getTransactionId(),
                Timestamp.from(cutoff), Timestamp.from(cutoff));
        if (links.size() != 1) return Flow.unknown(links.isEmpty()
                ? "SOURCE_LINK_MISSING" : "SOURCE_LINK_CONFLICT");
        Link link = links.get(0);
        if (!"VERIFIED".equals(link.state())) return Flow.unknown("MOCK_PARTIAL_OR_FAILED");
        if (!link.customer().equals(target.getExternalCustomerRef())
                || !link.account().equals(target.getSenderAccountRef())) {
            return Flow.unknown("TARGET_ACCOUNT_MISMATCH");
        }
        List<Source> sources = jdbc.query("""
                SELECT transaction_type, amount, external_customer_ref,
                    sender_account_ref, recipient_account_ref, processing_status,
                    occurred_at, created_at, approved_at
                FROM financial_transaction WHERE transaction_id = ?
                """, (rs, row) -> new Source(rs.getString(1), rs.getBigDecimal(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                time(rs.getTimestamp(7)), time(rs.getTimestamp(8)),
                time(rs.getTimestamp(9))), link.sourceId());
        if (sources.size() != 1) return Flow.unknown("SOURCE_TRANSACTION_MISSING");
        Source source = sources.get(0);
        if (!"APPROVED".equals(source.status()) || source.approvedAt() == null
                || source.approvedAt().isAfter(cutoff) || source.createdAt().isAfter(cutoff)
                || !source.occurredAt().isBefore(cutoff)
                || source.occurredAt().isBefore(cutoff.minus(window))) {
            return Flow.unknown("SOURCE_NOT_APPROVED_AS_OF_CUTOFF");
        }
        if ("SCN006".equals(scenario)) {
            if (!("ACCOUNT_TRANSFER".equals(source.type())
                    || "OPEN_BANKING_TRANSFER".equals(source.type()))
                    || !link.account().equals(source.recipientAccount())
                    || source.amount().compareTo(new BigDecimal("10000000")) < 0) {
                return Flow.unknown("INBOUND_SOURCE_INVALID");
            }
        } else if (!"LOAN_DISBURSED".equals(source.type())
                || !link.account().equals(source.senderAccount())
                || !link.customer().equals(source.customer())) {
            return Flow.unknown("LOAN_SOURCE_INVALID");
        }
        UUID proof = coverage(link, cutoff.minus(window), cutoff);
        if (proof == null) return Flow.unknown("ACCOUNT_HISTORY_INCOMPLETE");
        UUID beneficiary = null;
        if ("SCN007".equals(scenario)) {
            beneficiary = newBeneficiary(target, source.occurredAt(), cutoff);
            if (beneficiary == null) return Flow.unknown("BENEFICIARY_NOT_NEW");
        }
        List<Use> uses = jdbc.query("""
                SELECT linked.transaction_id, linked.amount, linked.transaction_type,
                    linked.external_customer_ref, linked.sender_account_ref,
                    linked.processing_status, linked.occurred_at, linked.created_at,
                    linked.approved_at, link.assertion_state,
                    link.external_customer_ref, link.account_ref,
                    (SELECT COUNT(*) FROM fds_mock_fund_link other
                     WHERE other.scenario_code = link.scenario_code
                       AND other.target_transaction_id = link.target_transaction_id
                       AND other.occurred_at <= ? AND other.created_at <= ?) AS link_count
                FROM fds_mock_fund_link link
                JOIN financial_transaction linked
                  ON linked.transaction_id = link.target_transaction_id
                WHERE link.scenario_code = ? AND link.source_transaction_id = ?
                  AND link.occurred_at <= ? AND link.created_at <= ?
                  AND linked.occurred_at >= ? AND linked.occurred_at < ?
                  AND linked.transaction_id <> ?
                ORDER BY linked.transaction_id ASC LIMIT 101
                """, (rs, row) -> new Use(rs.getObject(1, UUID.class), rs.getBigDecimal(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                time(rs.getTimestamp(7)), time(rs.getTimestamp(8)),
                time(rs.getTimestamp(9)), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getLong(13)),
                Timestamp.from(cutoff), Timestamp.from(cutoff), scenario,
                link.sourceId(), Timestamp.from(cutoff), Timestamp.from(cutoff),
                Timestamp.from(source.occurredAt()), Timestamp.from(cutoff),
                target.getTransactionId());
        if (uses.size() > 100) return Flow.unknown("LINKED_HISTORY_LIMIT");
        BigDecimal linked = target.getAmount();
        List<UUID> priorIds = new ArrayList<>();
        for (Use use : uses) {
            if (!"APPROVED".equals(use.status())) continue;
            if (!"VERIFIED".equals(use.linkState()) || use.linkCount() != 1
                    || use.createdAt().isAfter(cutoff) || use.approvedAt() == null
                    || use.approvedAt().isAfter(cutoff)
                    || !link.customer().equals(use.linkCustomer())
                    || !link.account().equals(use.linkAccount())
                    || !link.customer().equals(use.customer())
                    || !link.account().equals(use.senderAccount())
                    || !targetType(scenario, use.type())) {
                return Flow.unknown("LINKED_HISTORY_UNPROVEN");
            }
            linked = linked.add(use.amount());
            priorIds.add(use.id());
        }
        if (linked.compareTo(source.amount()) > 0) {
            return Flow.unknown("SOURCE_OVERALLOCATED");
        }
        int ratio = linked.multiply(new BigDecimal("10000"))
                .divide(source.amount(), 0, RoundingMode.DOWN).intValueExact();
        return new Flow("AVAILABLE", "VERIFIED_SOURCE_TO_USE", link.sourceId(),
                link.id(), proof, beneficiary, source.occurredAt(), source.approvedAt(),
                source.amount(), linked, ratio, priorIds);
    }

    private UUID coverage(Link link, Instant start, Instant cutoff) {
        List<UUID> rows = jdbc.query("""
                WITH proofs AS (
                    SELECT fact_id, external_customer_ref, occurred_at
                    FROM fds_mock_fact
                    WHERE fact_type = 'ACCOUNT_FLOW_HISTORY_COMPLETE'
                      AND account_ref = ? AND assertion_state = 'COMPLETE'
                      AND coverage_from <= ? AND coverage_through >= ?
                      AND occurred_at <= ? AND created_at <= ?
                )
                SELECT fact_id FROM proofs
                WHERE external_customer_ref = ?
                  AND (SELECT COUNT(DISTINCT external_customer_ref) FROM proofs) = 1
                ORDER BY occurred_at DESC, fact_id ASC LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class),
                link.account(), Timestamp.from(start), Timestamp.from(cutoff),
                Timestamp.from(cutoff), Timestamp.from(cutoff), link.customer());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID newBeneficiary(FinancialTransaction target, Instant sourceAt,
                                Instant cutoff) {
        if (target.getRecipientAccountRef() == null) return null;
        List<UUID> events = jdbc.query("""
                SELECT event_id FROM behavior_event
                WHERE event_type = 'BENEFICIARY_REGISTERED'
                  AND external_customer_ref = ? AND account_ref = ?
                  AND beneficiary_ref = ? AND occurred_at > ?
                  AND occurred_at < ? AND created_at <= ?
                ORDER BY occurred_at DESC, event_id ASC LIMIT 2
                """, (rs, row) -> rs.getObject(1, UUID.class),
                target.getExternalCustomerRef(), target.getSenderAccountRef(),
                target.getRecipientAccountRef(), Timestamp.from(sourceAt),
                Timestamp.from(cutoff), Timestamp.from(cutoff));
        if (events.size() != 1) return null;
        Long oldRegistrations = jdbc.queryForObject("""
                SELECT COUNT(*) FROM behavior_event
                WHERE event_type = 'BENEFICIARY_REGISTERED'
                  AND external_customer_ref = ? AND account_ref = ?
                  AND beneficiary_ref = ? AND occurred_at <= ?
                  AND created_at <= ?
                """, Long.class, target.getExternalCustomerRef(),
                target.getSenderAccountRef(), target.getRecipientAccountRef(),
                Timestamp.from(sourceAt), Timestamp.from(cutoff));
        if (oldRegistrations != 0) return null;
        Long prior = jdbc.queryForObject("""
                SELECT COUNT(*) FROM financial_transaction
                WHERE external_customer_ref = ? AND sender_account_ref = ?
                  AND recipient_account_ref = ? AND transaction_type IN
                    ('ACCOUNT_TRANSFER','OPEN_BANKING_TRANSFER')
                  AND processing_status = 'APPROVED'
                  AND occurred_at < ? AND created_at <= ?
                  AND (approved_at <= ? OR approved_at IS NULL)
                """, Long.class, target.getExternalCustomerRef(),
                target.getSenderAccountRef(), target.getRecipientAccountRef(),
                Timestamp.from(cutoff), Timestamp.from(cutoff), Timestamp.from(cutoff));
        return prior == 0 ? events.get(0) : null;
    }

    private static boolean targetType(String scenario, String type) {
        return "SCN006".equals(scenario) ? "ATM_WITHDRAWAL".equals(type)
                : "ACCOUNT_TRANSFER".equals(type)
                || "OPEN_BANKING_TRANSFER".equals(type);
    }

    private static Instant time(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Link(UUID id, UUID sourceId, String customer,
                        String account, String state) { }
    private record Source(String type, BigDecimal amount, String customer,
            String senderAccount, String recipientAccount, String status,
            Instant occurredAt, Instant createdAt, Instant approvedAt) { }
    private record Use(UUID id, BigDecimal amount, String type, String customer,
            String senderAccount, String status, Instant occurredAt,
            Instant createdAt, Instant approvedAt, String linkState,
            String linkCustomer, String linkAccount,
            long linkCount) { }
}
