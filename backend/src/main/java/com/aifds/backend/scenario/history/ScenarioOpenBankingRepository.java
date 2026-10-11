package com.aifds.backend.scenario.history;

import com.aifds.backend.scenario.ScenarioOpenBankingFacts;
import com.aifds.backend.scenario.ScenarioOpenBankingFacts.Stage;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact directional chain; no bank account ownership is inferred from a transfer. */
@Repository
public class ScenarioOpenBankingRepository {
    private final JdbcTemplate jdbc;

    public ScenarioOpenBankingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ScenarioOpenBankingFacts snapshot(FinancialTransaction target) {
        if (!(target.getTransactionType() == TransactionType.ACCOUNT_TRANSFER
                || target.getTransactionType() == TransactionType.OPEN_BANKING_TRANSFER)
                || target.getRecipientAccountRef() == null
                || target.getRecipientAccountRef().equals(target.getSenderAccountRef())) {
            return ScenarioOpenBankingFacts.unknown("NOT_OUTBOUND_RESEND");
        }
        Instant cutoff = target.getOccurredAt();
        List<Link> links = jdbc.query("""
                SELECT link_id, source_transaction_id, external_customer_ref,
                    account_ref, assertion_state
                FROM fds_mock_fund_link
                WHERE scenario_code = 'SCN008' AND target_transaction_id = ?
                  AND occurred_at <= ? AND created_at <= ?
                ORDER BY source_transaction_id ASC, link_id ASC LIMIT 101
                """, (rs, row) -> new Link(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5)), target.getTransactionId(),
                Timestamp.from(cutoff), Timestamp.from(cutoff));
        if (links.isEmpty()) return ScenarioOpenBankingFacts.unknown("SOURCE_LINK_MISSING");
        if (links.size() > 100) return ScenarioOpenBankingFacts.unknown("SOURCE_LINK_LIMIT");
        Set<UUID> sourceIds = new HashSet<>();
        Set<String> externalAccounts = new HashSet<>();
        List<Stage> stages = new ArrayList<>();
        BigDecimal concentration = BigDecimal.ZERO;
        Instant first = null;
        Instant last = null;
        for (Link link : links) {
            if (!sourceIds.add(link.sourceId())) {
                return ScenarioOpenBankingFacts.unknown("SOURCE_LINK_CONFLICT");
            }
            if (!"VERIFIED".equals(link.state())) {
                return ScenarioOpenBankingFacts.unknown("MOCK_PARTIAL_OR_FAILED");
            }
            if (!link.customer().equals(target.getExternalCustomerRef())
                    || !link.account().equals(target.getSenderAccountRef())) {
                return ScenarioOpenBankingFacts.unknown("CONCENTRATION_ACCOUNT_MISMATCH");
            }
            List<Source> sources = jdbc.query("""
                    SELECT transaction_type, external_customer_ref, sender_account_ref,
                        recipient_account_ref, processing_status, occurred_at,
                        created_at, approved_at, amount
                    FROM financial_transaction WHERE transaction_id = ?
                    """, (rs, row) -> new Source(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5),
                            time(rs.getTimestamp(6)), time(rs.getTimestamp(7)),
                            time(rs.getTimestamp(8)), rs.getBigDecimal(9)), link.sourceId());
            if (sources.size() != 1) return ScenarioOpenBankingFacts.unknown("SOURCE_MISSING");
            Source source = sources.get(0);
            if (!"OPEN_BANKING_TRANSFER".equals(source.type())
                    || !"APPROVED".equals(source.status())
                    || !link.customer().equals(source.customer())
                    || !link.account().equals(source.recipient())
                    || source.approvedAt() == null || source.approvedAt().isAfter(cutoff)
                    || source.createdAt().isAfter(cutoff)
                    || !source.occurredAt().isBefore(cutoff)) {
                return ScenarioOpenBankingFacts.unknown("SOURCE_NOT_APPROVED_AS_OF_CUTOFF");
            }
            UUID owner = ownerAssertion(source.sender(), link.customer(), source.occurredAt());
            if (owner == null) return ScenarioOpenBankingFacts.unknown("EXTERNAL_OWNERSHIP_UNPROVEN");
            externalAccounts.add(source.sender());
            stages.add(new Stage(link.sourceId(), owner, link.id(), source.occurredAt(),
                    source.approvedAt(), source.amount()));
            concentration = concentration.add(source.amount());
            if (first == null || source.occurredAt().isBefore(first)) first = source.occurredAt();
            if (last == null || source.occurredAt().isAfter(last)) last = source.occurredAt();
        }
        if (externalAccounts.size() < 2) {
            return ScenarioOpenBankingFacts.unknown("DISTINCT_EXTERNAL_ACCOUNT_INSUFFICIENT");
        }
        if (first.isBefore(last.minus(Duration.ofMinutes(30)))
                || last.isBefore(cutoff.minus(Duration.ofMinutes(15)))) {
            return ScenarioOpenBankingFacts.unknown("FLOW_WINDOW_EXPIRED");
        }
        UUID history = historyCoverage(target, last.minus(Duration.ofMinutes(30)), cutoff);
        if (history == null) return ScenarioOpenBankingFacts.unknown("ACCOUNT_HISTORY_INCOMPLETE");
        if (target.getAmount().compareTo(concentration) > 0) {
            return ScenarioOpenBankingFacts.unknown("SOURCE_OVERALLOCATED");
        }
        int ratio = target.getAmount().multiply(new BigDecimal("10000"))
                .divide(concentration, 0, RoundingMode.DOWN).intValueExact();
        stages.sort(java.util.Comparator.comparing(Stage::occurredAt)
                .thenComparing(Stage::sourceTransactionId));
        return new ScenarioOpenBankingFacts("SCN008-contract-v1", "AVAILABLE",
                "VERIFIED_OPEN_BANKING_CHAIN", history, first, last, concentration,
                target.getAmount(), ratio, stages);
    }

    private UUID ownerAssertion(String account, String customer, Instant sourceAt) {
        List<Owner> assertions = jdbc.query("""
                SELECT assertion_id, external_customer_ref, registration_state,
                    ownership_state FROM fds_mock_open_account
                WHERE external_account_ref = ? AND occurred_at <= ? AND created_at <= ?
                ORDER BY occurred_at DESC, assertion_id ASC LIMIT 101
                """, (rs, row) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4)), account,
                Timestamp.from(sourceAt), Timestamp.from(sourceAt));
        if (assertions.size() != 1) return null;
        Owner owner = assertions.get(0);
        return owner.customer().equals(customer)
                && "VERIFIED".equals(owner.registration())
                && "VERIFIED".equals(owner.ownership()) ? owner.id() : null;
    }

    private UUID historyCoverage(FinancialTransaction target, Instant start, Instant cutoff) {
        List<UUID> rows = jdbc.query("""
                WITH proofs AS (
                    SELECT fact_id, external_customer_ref, occurred_at
                    FROM fds_mock_fact WHERE fact_type = 'ACCOUNT_FLOW_HISTORY_COMPLETE'
                      AND account_ref = ? AND assertion_state = 'COMPLETE'
                      AND coverage_from <= ? AND coverage_through >= ?
                      AND occurred_at <= ? AND created_at <= ?
                )
                SELECT fact_id FROM proofs WHERE external_customer_ref = ?
                  AND (SELECT COUNT(DISTINCT external_customer_ref) FROM proofs) = 1
                ORDER BY occurred_at DESC, fact_id ASC LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class),
                target.getSenderAccountRef(), Timestamp.from(start), Timestamp.from(cutoff),
                Timestamp.from(cutoff), Timestamp.from(cutoff),
                target.getExternalCustomerRef());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant time(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Link(UUID id, UUID sourceId, String customer,
                        String account, String state) { }
    private record Source(String type, String customer, String sender,
            String recipient, String status, Instant occurredAt, Instant createdAt,
            Instant approvedAt, BigDecimal amount) { }
    private record Owner(UUID id, String customer, String registration,
                         String ownership) { }
}
