package com.aifds.backend.scenario.mock;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class MockFactIntakeService {
    private final MockFactRepository facts;
    private final BehaviorEventRepository behaviors;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public MockFactIntakeService(MockFactRepository facts,
                                 BehaviorEventRepository behaviors,
                                 ObjectMapper mapper, Clock clock, JdbcTemplate jdbc) {
        this.facts = facts;
        this.behaviors = behaviors;
        this.mapper = mapper;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Transactional
    public IntakeResult ingest(MockFactRequest request) {
        validate(request);
        String fingerprint = fingerprint(request);
        var existing = facts.findByFactId(request.factId());
        if (existing.isPresent()) {
            if (!fingerprint.equals(existing.get().getRequestFingerprint())) {
                throw conflict();
            }
            return new IntakeResult(request.factId(), false);
        }
        try {
            int inserted = jdbc.update("""
                    INSERT INTO fds_mock_fact (fact_id, fact_type, external_customer_ref,
                      account_ref, device_ref, beneficiary_ref, behavior_event_id,
                      occurred_at, coverage_from, coverage_through, assertion_state,
                      limit_scope, previous_limit, new_limit, country_code, request_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, request.factId(), request.factType().name(),
                    request.externalCustomerRef(), request.accountRef(), request.deviceRef(),
                    request.beneficiaryRef(), request.behaviorEventId(),
                    java.sql.Timestamp.from(request.occurredAt()),
                    timestamp(request.coverageFrom()), timestamp(request.coverageThrough()),
                    request.assertionState(), request.limitScope(), request.previousLimit(),
                    request.newLimit(), request.countryCode(), fingerprint);
            if (inserted == 1) return new IntakeResult(request.factId(), true);
            var raced = facts.findByFactId(request.factId());
            if (raced.isPresent()
                    && fingerprint.equals(raced.get().getRequestFingerprint())) {
                return new IntakeResult(request.factId(), false);
            }
            throw conflict();
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private void validate(MockFactRequest r) {
        if (r == null || r.factId() == null || r.factId().version() != 4
                || r.factType() == null || !reference(r.externalCustomerRef())
                || r.occurredAt() == null || r.occurredAt().isAfter(clock.instant().plus(Duration.ofMinutes(5)))
                || !optionalReference(r.accountRef()) || !optionalReference(r.deviceRef())
                || !optionalReference(r.beneficiaryRef()) || r.beneficiaryRef() != null) {
            throw invalid();
        }
        switch (r.factType()) {
            case TRANSFER_HISTORY_COMPLETE, DEVICE_HISTORY_COMPLETE -> {
                if (r.accountRef() != null || r.deviceRef() != null || r.behaviorEventId() != null
                        || r.coverageFrom() == null || r.coverageFrom().isAfter(r.occurredAt())
                        || r.coverageThrough() == null || r.coverageThrough().isBefore(r.occurredAt())
                        || r.coverageThrough().isAfter(clock.instant().plus(Duration.ofMinutes(5)))
                        || !"COMPLETE".equals(r.assertionState()) || hasLimit(r)
                        || r.countryCode() != null) throw invalid();
            }
            case INBOUND_ACCOUNT_HISTORY_COMPLETE, ACCOUNT_FLOW_HISTORY_COMPLETE -> {
                if (r.accountRef() == null || r.deviceRef() != null
                        || r.behaviorEventId() != null || r.coverageFrom() == null
                        || r.coverageFrom().isAfter(r.occurredAt())
                        || r.coverageThrough() == null
                        || r.coverageThrough().isBefore(r.occurredAt())
                        || r.coverageThrough().isAfter(clock.instant().plus(Duration.ofMinutes(5)))
                        || !"COMPLETE".equals(r.assertionState()) || hasLimit(r)
                        || r.countryCode() != null) throw invalid();
            }
            case DEVICE_TRUST -> {
                if (r.deviceRef() == null || r.behaviorEventId() == null
                        || !("VERIFIED".equals(r.assertionState())
                        || "UNVERIFIED".equals(r.assertionState()))
                        || r.coverageFrom() != null || r.coverageThrough() != null
                        || hasLimit(r) || r.countryCode() != null) {
                    throw invalid();
                }
                requireLinkedBehavior(r, BehaviorEventType.LOGIN, BehaviorEventType.DEVICE_REGISTERED);
            }
            case LIMIT_CHANGE -> {
                if (r.accountRef() == null || r.deviceRef() != null || r.behaviorEventId() == null
                        || !("SUCCEEDED".equals(r.assertionState())
                        || "FAILED".equals(r.assertionState()))
                        || !("PER_TRANSFER".equals(r.limitScope()) || "DAILY".equals(r.limitScope()))
                        || !positiveInteger(r.previousLimit()) || !positiveInteger(r.newLimit())
                        || r.coverageFrom() != null || r.coverageThrough() != null
                        || r.countryCode() != null) throw invalid();
                requireLinkedBehavior(r, BehaviorEventType.TRANSFER_LIMIT_CHANGED);
            }
            case IDENTITY_CHECK -> {
                if (r.accountRef() == null || r.behaviorEventId() == null
                        || !("VERIFIED".equals(r.assertionState())
                        || "FAILED".equals(r.assertionState()))
                        || r.coverageFrom() != null || r.coverageThrough() != null
                        || hasLimit(r) || r.countryCode() != null) {
                    throw invalid();
                }
                requireLinkedBehavior(r, BehaviorEventType.PASSWORD_CHANGED);
            }
            case APP_SECURITY -> {
                if (r.accountRef() != null || r.deviceRef() == null || r.behaviorEventId() != null
                        || !("ALERT".equals(r.assertionState()) || "CLEAR".equals(r.assertionState()))
                        || r.coverageFrom() != null || r.coverageThrough() != null
                        || hasLimit(r) || r.countryCode() != null) {
                    throw invalid();
                }
                requireOwnedDevice(r);
            }
            case NETWORK_REGION -> {
                if (r.accountRef() != null || r.deviceRef() == null || r.behaviorEventId() != null
                        || !("OVERSEAS".equals(r.assertionState())
                        || "DOMESTIC".equals(r.assertionState()))
                        || r.countryCode() == null || !r.countryCode().matches("^[A-Z]{2}$")
                        || ("DOMESTIC".equals(r.assertionState()) != "KR".equals(r.countryCode()))
                        || r.coverageFrom() != null || r.coverageThrough() != null
                        || hasLimit(r)) throw invalid();
                requireOwnedDevice(r);
            }
        }
    }

    private void requireOwnedDevice(MockFactRequest request) {
        if (behaviors.countDeviceOwnershipEvidence(request.externalCustomerRef(),
                request.deviceRef(), Set.of(BehaviorEventType.LOGIN,
                        BehaviorEventType.DEVICE_REGISTERED), request.occurredAt(),
                clock.instant()) == 0) throw invalid();
    }

    private void requireLinkedBehavior(MockFactRequest r, BehaviorEventType... allowedTypes) {
        BehaviorEvent event = behaviors.findByEventId(r.behaviorEventId()).orElseThrow(MockFactIntakeService::invalid);
        boolean allowed = false;
        for (BehaviorEventType type : allowedTypes) allowed |= event.getEventType() == type;
        if (!allowed || !Objects.equals(event.getExternalCustomerRef(), r.externalCustomerRef())
                || (r.accountRef() != null && !Objects.equals(event.getAccountRef(), r.accountRef()))
                || (r.deviceRef() != null && !Objects.equals(event.getDeviceRef(), r.deviceRef()))
                || !event.getOccurredAt().equals(r.occurredAt())) throw invalid();
    }

    private boolean hasLimit(MockFactRequest r) {
        return r.limitScope() != null || r.previousLimit() != null || r.newLimit() != null;
    }

    private boolean positiveInteger(BigDecimal value) {
        return value != null && value.signum() > 0 && value.scale() <= 0
                && value.compareTo(new BigDecimal("999999999999999")) <= 0;
    }

    private boolean reference(String value) {
        return value != null && value.length() >= 1 && value.length() <= 128
                && value.equals(value.trim());
    }

    private boolean optionalReference(String value) { return value == null || reference(value); }

    private String fingerprint(MockFactRequest request) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Mock fact fingerprint failed", exception);
        }
    }

    private static MockFactException invalid() {
        return new MockFactException(HttpStatus.BAD_REQUEST, "INVALID_MOCK_FACT");
    }

    private static MockFactException conflict() {
        return new MockFactException(HttpStatus.CONFLICT, "MOCK_FACT_CONFLICT");
    }

    public record IntakeResult(UUID factId, boolean created) { }
}
