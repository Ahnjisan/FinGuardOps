package com.aifds.backend.scenario;

import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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

    public ScenarioCaseCorrelationService(DetectionEvidenceRepository evidence,
            FraudCaseRepository cases, JdbcTemplate jdbc) {
        this.evidence = evidence;
        this.cases = cases;
        this.jdbc = jdbc;
    }

    public Optional<Correlation> correlation(FinancialTransaction tx) {
        var result = tx.getAdoptedDetectionResult();
        if (result == null || !"rule-ml-policy-v3".equals(result.getScoringPolicyVersion())
                || tx.getRecipientAccountRef() == null) return Optional.empty();
        List<DetectionEvidence> rows = evidence
                .findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                        result.getId(), DetectionEvidenceType.RULE);
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
                java.sql.Timestamp.from(tx.getOccurredAt().minus(Duration.ofMinutes(30))),
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
}
