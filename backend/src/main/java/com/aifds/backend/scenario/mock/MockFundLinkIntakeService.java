package com.aifds.backend.scenario.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class MockFundLinkIntakeService {
    private static final Set<String> SCENARIOS = Set.of("SCN006", "SCN007", "SCN008");
    private static final Set<String> STATES = Set.of("VERIFIED", "FAILED", "PARTIAL");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MockFundLinkIntakeService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public boolean ingest(MockFundLinkRequest request) {
        validate(request);
        String fingerprint = fingerprint(request);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO fds_mock_fund_link (link_id, scenario_code,
                      source_transaction_id, target_transaction_id,
                      external_customer_ref, account_ref, assertion_state,
                      occurred_at, request_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, request.linkId(), request.scenarioCode(),
                    request.sourceTransactionId(), request.targetTransactionId(),
                    request.externalCustomerRef(), request.accountRef(),
                    request.assertionState(), Timestamp.from(request.occurredAt()),
                    fingerprint);
            if (inserted == 1) return true;
            var stored = jdbc.query("""
                    SELECT request_fingerprint FROM fds_mock_fund_link WHERE link_id = ?
                    """, (rs, row) -> rs.getString(1), request.linkId());
            if (stored.size() == 1 && fingerprint.equals(stored.get(0))) return false;
            throw conflict();
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    private void validate(MockFundLinkRequest r) {
        if (r == null || !uuid4(r.linkId()) || !uuid4(r.sourceTransactionId())
                || !uuid4(r.targetTransactionId())
                || r.sourceTransactionId().equals(r.targetTransactionId())
                || !SCENARIOS.contains(r.scenarioCode())
                || !STATES.contains(r.assertionState())
                || !reference(r.externalCustomerRef()) || !reference(r.accountRef())
                || r.occurredAt() == null
                || r.occurredAt().isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw invalid();
        }
    }

    private static boolean uuid4(UUID value) {
        return value != null && value.version() == 4;
    }

    private static boolean reference(String value) {
        return value != null && value.length() <= 128 && !value.isEmpty()
                && value.equals(value.trim());
    }

    private String fingerprint(MockFundLinkRequest request) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Mock fund link fingerprint failed", exception);
        }
    }

    private static MockFactException invalid() {
        return new MockFactException(HttpStatus.BAD_REQUEST, "INVALID_MOCK_FUND_LINK");
    }

    private static MockFactException conflict() {
        return new MockFactException(HttpStatus.CONFLICT, "MOCK_FUND_LINK_CONFLICT");
    }
}
