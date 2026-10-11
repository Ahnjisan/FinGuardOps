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

@Service
public class MockOpenAccountIntakeService {
    private static final Set<String> STATES = Set.of("VERIFIED", "PARTIAL", "FAILED");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MockOpenAccountIntakeService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public boolean ingest(MockOpenAccountRequest request) {
        validate(request);
        String fingerprint = fingerprint(request);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO fds_mock_open_account (assertion_id, external_customer_ref,
                        external_account_ref, registration_state, ownership_state,
                        occurred_at, request_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, request.assertionId(), request.externalCustomerRef(),
                    request.externalAccountRef(), request.registrationState(),
                    request.ownershipState(), Timestamp.from(request.occurredAt()),
                    fingerprint);
            if (inserted == 1) return true;
            var stored = jdbc.query("""
                    SELECT request_fingerprint FROM fds_mock_open_account
                    WHERE assertion_id = ?
                    """, (rs, row) -> rs.getString(1), request.assertionId());
            if (stored.size() == 1 && fingerprint.equals(stored.get(0))) return false;
            throw conflict();
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    private void validate(MockOpenAccountRequest r) {
        if (r == null || r.assertionId() == null || r.assertionId().version() != 4
                || !reference(r.externalCustomerRef())
                || !reference(r.externalAccountRef())
                || !STATES.contains(r.registrationState())
                || !STATES.contains(r.ownershipState())
                || r.occurredAt() == null
                || r.occurredAt().isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw new MockFactException(HttpStatus.BAD_REQUEST, "INVALID_OPEN_ACCOUNT_MOCK");
        }
    }

    private static boolean reference(String value) {
        return value != null && value.length() <= 128 && !value.isEmpty()
                && value.equals(value.trim());
    }

    private String fingerprint(MockOpenAccountRequest request) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Mock open account fingerprint failed", exception);
        }
    }

    private static MockFactException conflict() {
        return new MockFactException(HttpStatus.CONFLICT, "OPEN_ACCOUNT_MOCK_CONFLICT");
    }
}
