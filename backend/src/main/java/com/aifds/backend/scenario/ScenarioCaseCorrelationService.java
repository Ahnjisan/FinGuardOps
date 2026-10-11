package com.aifds.backend.scenario;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Exact, versioned correlation; no raw customer/account/device reference is stored. */
@Service
public class ScenarioCaseCorrelationService {
    private final DetectionEvidenceRepository evidence;
    private final FraudCaseRepository cases;
    private final JdbcTemplate jdbc;
    private final AuditLogPersistenceService auditLogs;

    public ScenarioCaseCorrelationService(DetectionEvidenceRepository evidence,
            FraudCaseRepository cases, JdbcTemplate jdbc,
            AuditLogPersistenceService auditLogs) {
        this.evidence = evidence;
        this.cases = cases;
        this.jdbc = jdbc;
        this.auditLogs = auditLogs;
    }

    public Optional<Correlation> correlation(FinancialTransaction tx) {
        var result = tx.getAdoptedDetectionResult();
        if (result == null || !("rule-ml-policy-v3".equals(result.getScoringPolicyVersion())
                || "rule-ml-policy-v4".equals(result.getScoringPolicyVersion())
                || "rule-ml-policy-v5".equals(result.getScoringPolicyVersion()))) return Optional.empty();
        List<DetectionEvidence> rows = evidence
                .findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                        result.getId(), DetectionEvidenceType.RULE);
        if ("rule-ml-policy-v5".equals(result.getScoringPolicyVersion())) {
            for (var entry : List.of(
                    new String[] {RuleV1ContractRegistry.VERIFIED_INBOUND_ATM_DRAIN, "SCN006"},
                    new String[] {RuleV1ContractRegistry.VERIFIED_LOAN_NEW_RECIPIENT_DRAIN, "SCN007"})) {
                var hit = rows.stream().filter(row -> entry[0].equals(row.getRuleCode()))
                        .findFirst();
                if (hit.isPresent()) {
                    return Optional.of(new Correlation(entry[1], key(entry[1],
                            required(hit.get().getObservationSummary(), "sourceTransactionId"))));
                }
            }
        }
        if (tx.getRecipientAccountRef() == null) return Optional.empty();
        if ("rule-ml-policy-v4".equals(result.getScoringPolicyVersion())) {
            if (rows.stream().anyMatch(row ->
                    RuleV1ContractRegistry.DISTRIBUTED_OUTBOUND_WINDOW.equals(row.getRuleCode()))) {
                return Optional.of(new Correlation("SCN004", key("SCN004",
                        tx.getExternalCustomerRef(), tx.getSenderAccountRef())));
            }
            if (rows.stream().anyMatch(row ->
                    RuleV1ContractRegistry.MANY_CUSTOMER_INBOUND_WINDOW.equals(row.getRuleCode()))) {
                return Optional.of(new Correlation("SCN005", key("SCN005",
                        tx.getRecipientAccountRef())));
            }
        }
        if (rows.stream().anyMatch(row ->
                RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT.equals(
                        row.getRuleCode()))) return Optional.empty();
        var second = rows.stream().filter(row ->
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT.equals(
                        row.getRuleCode())).findFirst();
        if (second.isPresent()) {
            JsonNode observation = second.get().getObservationSummary();
            return Optional.of(new Correlation("SCN002", key("SCN002",
                    tx.getExternalCustomerRef(), tx.getSenderAccountRef(),
                    required(observation, "passwordEventId"),
                    required(observation, "limitEventId"))));
        }
        var first = rows.stream().filter(row ->
                RuleV1ContractRegistry.RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT.equals(
                        row.getRuleCode())).findFirst();
        if (first.isPresent() && tx.getDeviceRef() != null) {
            return Optional.of(new Correlation("SCN001", key("SCN001",
                    tx.getExternalCustomerRef(), tx.getSenderAccountRef(),
                    tx.getDeviceRef(), tx.getRecipientAccountRef(),
                    required(first.get().getObservationSummary(), "deviceEventId"))));
        }
        return Optional.empty();
    }

    /** Caller holds a transaction; the advisory lock serializes equal keys. */
    public Optional<FraudCase> lockRepresentative(Correlation correlation,
            FinancialTransaction tx) {
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) held",
                Integer.class, correlation.key());
        List<UUID> ids = jdbc.query("""
                SELECT fc.case_id FROM scenario_case_correlation cc
                JOIN fraud_case fc ON fc.id = cc.fraud_case_id
                WHERE cc.correlation_key = ? AND cc.scenario_code = ?
                  AND cc.anchor_at >= ? AND cc.anchor_at <= ?
                  AND fc.case_status IN ('OPEN','IN_REVIEW','ADDITIONAL_INFORMATION_REQUIRED')
                ORDER BY cc.anchor_at ASC, fc.case_id ASC
                """, (rs, n) -> rs.getObject(1, UUID.class), correlation.key(),
                correlation.scenarioCode(),
                java.sql.Timestamp.from(tx.getOccurredAt().minus(
                        "SCN005".equals(correlation.scenarioCode())
                        ? Duration.ofHours(24)
                                : "SCN004".equals(correlation.scenarioCode())
                                        ? Duration.ofMinutes(10)
                                        : "SCN007".equals(correlation.scenarioCode())
                                                ? Duration.ofMinutes(60) : Duration.ofMinutes(30))),
                java.sql.Timestamp.from(tx.getOccurredAt()));
        if (ids.isEmpty()) return Optional.empty();
        var locked = cases.findAllByCaseIdsForUpdate(ids);
        for (UUID id : ids) {
            for (FraudCase candidate : locked) {
                if (candidate.getCaseId().equals(id) && candidate.isActive()) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public void register(Correlation correlation, FraudCase fraudCase,
            FinancialTransaction tx) {
        jdbc.update("""
                INSERT INTO scenario_case_correlation
                  (fraud_case_id, correlation_key, scenario_code, anchor_at)
                VALUES (?, ?, ?, ?)
                """, fraudCase.getId(), correlation.key(), correlation.scenarioCode(),
                java.sql.Timestamp.from(tx.getOccurredAt()));
    }

    /** Attach only as-of approved transfers sharing the proved aggregation key/window. */
    public void attachRelated(Correlation correlation, FraudCase fraudCase,
                              FinancialTransaction tx, Instant linkedAt) {
        if ("SCN006".equals(correlation.scenarioCode())
                || "SCN007".equals(correlation.scenarioCode())) {
            attachFundFlow(correlation, fraudCase, tx, linkedAt);
            return;
        }
        if (!("SCN004".equals(correlation.scenarioCode())
                || "SCN005".equals(correlation.scenarioCode()))) return;
        boolean outbound = "SCN004".equals(correlation.scenarioCode());
        String predicate = outbound
                ? "prior.external_customer_ref = ? AND prior.sender_account_ref = ?"
                : "prior.recipient_account_ref = ?";
        Object[] parameters = outbound
                ? new Object[] {tx.getExternalCustomerRef(), tx.getSenderAccountRef(),
                        java.sql.Timestamp.from(tx.getOccurredAt().minus(Duration.ofMinutes(10))),
                        java.sql.Timestamp.from(tx.getOccurredAt()),
                        java.sql.Timestamp.from(tx.getOccurredAt()),
                        java.sql.Timestamp.from(tx.getOccurredAt())}
                : new Object[] {tx.getRecipientAccountRef(),
                        java.sql.Timestamp.from(tx.getOccurredAt().minus(Duration.ofHours(24))),
                        java.sql.Timestamp.from(tx.getOccurredAt()),
                        java.sql.Timestamp.from(tx.getOccurredAt()),
                        java.sql.Timestamp.from(tx.getOccurredAt())};
        List<RelatedTransfer> related = jdbc.query("""
                SELECT prior.id, prior.transaction_id FROM financial_transaction prior
                WHERE %s AND prior.transaction_type IN
                    ('ACCOUNT_TRANSFER','OPEN_BANKING_TRANSFER')
                  AND prior.currency_code = 'KRW'
                  AND prior.processing_status = 'APPROVED'
                  AND prior.occurred_at >= ? AND prior.occurred_at < ?
                  AND prior.created_at <= ? AND prior.approved_at <= ?
                ORDER BY prior.id ASC LIMIT 1001
                """.formatted(predicate), (rs, row) -> new RelatedTransfer(
                        rs.getLong(1), rs.getObject(2, UUID.class)), parameters);
        if (related.size() > 1000) {
            throw new IllegalStateException("Scenario related transfer limit exceeded");
        }
        for (RelatedTransfer prior : related) {
            linkWithAudit(fraudCase, tx, linkedAt, prior);
        }
    }

    private void attachFundFlow(Correlation correlation, FraudCase fraudCase,
                                FinancialTransaction tx, Instant linkedAt) {
        var result = tx.getAdoptedDetectionResult();
        String code = "SCN006".equals(correlation.scenarioCode())
                ? RuleV1ContractRegistry.VERIFIED_INBOUND_ATM_DRAIN
                : RuleV1ContractRegistry.VERIFIED_LOAN_NEW_RECIPIENT_DRAIN;
        JsonNode observation = evidence
                .findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                        result.getId(), DetectionEvidenceType.RULE).stream()
                .filter(row -> code.equals(row.getRuleCode())).findFirst().orElseThrow()
                .getObservationSummary();
        List<UUID> ids = new java.util.ArrayList<>();
        ids.add(UUID.fromString(required(observation, "sourceTransactionId")));
        JsonNode priors = observation.path("linkedPriorTransactionIds");
        if (!priors.isArray() || priors.size() > 100) {
            throw new IllegalStateException("Invalid linked fund flow history");
        }
        for (JsonNode prior : priors) ids.add(UUID.fromString(prior.asText()));
        for (UUID id : ids) {
            List<RelatedTransfer> matches = jdbc.query("""
                    SELECT id, transaction_id FROM financial_transaction
                    WHERE transaction_id = ? AND processing_status = 'APPROVED'
                      AND occurred_at < ? AND created_at <= ? AND approved_at <= ?
                    """, (rs, row) -> new RelatedTransfer(
                            rs.getLong(1), rs.getObject(2, UUID.class)), id,
                    java.sql.Timestamp.from(tx.getOccurredAt()),
                    java.sql.Timestamp.from(tx.getOccurredAt()),
                    java.sql.Timestamp.from(tx.getOccurredAt()));
            if (matches.size() != 1) throw new IllegalStateException("Fund flow anchor changed");
            linkWithAudit(fraudCase, tx, linkedAt, matches.get(0));
        }
    }

    private void linkWithAudit(FraudCase fraudCase, FinancialTransaction tx,
                               Instant linkedAt, RelatedTransfer prior) {
        Long priorId = prior.id();
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) held",
                Integer.class, "case-related:" + priorId);
        int inserted = jdbc.update("""
                INSERT INTO case_transaction
                  (fraud_case_id, financial_transaction_id, linked_at)
                SELECT ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM case_transaction existing
                    JOIN fraud_case active ON active.id = existing.fraud_case_id
                    WHERE existing.financial_transaction_id = ?
                      AND active.case_status IN
                          ('OPEN','IN_REVIEW','ADDITIONAL_INFORMATION_REQUIRED'))
                ON CONFLICT (fraud_case_id, financial_transaction_id) DO NOTHING
                """, fraudCase.getId(), priorId,
                java.sql.Timestamp.from(linkedAt), priorId);
        if (inserted == 1) {
            var result = tx.getAdoptedDetectionResult();
            auditLogs.append(new AuditLogDraft(AuditActorType.SYSTEM,
                    AuditLog.SYSTEM_ACTOR_ID, AuditAction.CASE_TRANSACTION_LINKED,
                    AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                    AuditTargetType.FRAUD_CASE, fraudCase.getCaseId(),
                    prior.transactionId(), fraudCase.getCaseId(),
                    result.getAnalysisTraceId(), null,
                    JsonNodeFactory.instance.objectNode().put("linked", true),
                    JsonNodeFactory.instance.objectNode()
                            .put("detectionResultId", result.getDetectionResultId().toString())
                            .put("detectionResultVersion", result.getDetectionResultVersion())));
        }
    }

    private static String required(JsonNode observation, String field) {
        String value = observation.path(field).asText();
        UUID parsed = UUID.fromString(value);
        if (parsed.version() != 4 || !parsed.toString().equals(value)) {
            throw new IllegalStateException("Scenario anchor is invalid");
        }
        return value;
    }

    private static String key(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null) throw new IllegalStateException("Scenario key is incomplete");
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                md.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                md.update((byte) ':');
                md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Correlation(String scenarioCode, String key) { }

    private record RelatedTransfer(Long id, UUID transactionId) { }
}
