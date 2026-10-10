package com.aifds.backend.scenario.mock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fds_mock_fact")
public class MockFact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "fact_id", nullable = false, updatable = false)
    private UUID factId;
    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, updatable = false)
    private MockFactType factType;
    @Column(name = "source_code", nullable = false, updatable = false)
    private String sourceCode = "LOCAL_FDS_MOCK";
    @Column(name = "source_version", nullable = false, updatable = false)
    private String sourceVersion = "v1";
    @Column(name = "external_customer_ref", nullable = false, updatable = false)
    private String externalCustomerRef;
    @Column(name = "account_ref", updatable = false)
    private String accountRef;
    @Column(name = "device_ref", updatable = false)
    private String deviceRef;
    @Column(name = "beneficiary_ref", updatable = false)
    private String beneficiaryRef;
    @Column(name = "behavior_event_id", updatable = false)
    private UUID behaviorEventId;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "coverage_from", updatable = false)
    private Instant coverageFrom;
    @Column(name = "coverage_through", updatable = false)
    private Instant coverageThrough;
    @Column(name = "assertion_state", updatable = false)
    private String assertionState;
    @Column(name = "limit_scope", updatable = false)
    private String limitScope;
    @Column(name = "previous_limit", updatable = false)
    private BigDecimal previousLimit;
    @Column(name = "new_limit", updatable = false)
    private BigDecimal newLimit;
    @Column(name = "country_code", updatable = false)
    private String countryCode;
    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    protected MockFact() { }

    public MockFact(MockFactRequest request, String requestFingerprint) {
        factId = request.factId();
        factType = request.factType();
        externalCustomerRef = request.externalCustomerRef();
        accountRef = request.accountRef();
        deviceRef = request.deviceRef();
        beneficiaryRef = request.beneficiaryRef();
        behaviorEventId = request.behaviorEventId();
        occurredAt = request.occurredAt();
        coverageFrom = request.coverageFrom();
        coverageThrough = request.coverageThrough();
        assertionState = request.assertionState();
        limitScope = request.limitScope();
        previousLimit = request.previousLimit();
        newLimit = request.newLimit();
        countryCode = request.countryCode();
        this.requestFingerprint = requestFingerprint;
    }

    public UUID getFactId() { return factId; }
    public MockFactType getFactType() { return factType; }
    public String getSourceCode() { return sourceCode; }
    public String getSourceVersion() { return sourceVersion; }
    public String getExternalCustomerRef() { return externalCustomerRef; }
    public String getAccountRef() { return accountRef; }
    public String getDeviceRef() { return deviceRef; }
    public String getBeneficiaryRef() { return beneficiaryRef; }
    public UUID getBehaviorEventId() { return behaviorEventId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCoverageFrom() { return coverageFrom; }
    public Instant getCoverageThrough() { return coverageThrough; }
    public String getAssertionState() { return assertionState; }
    public String getLimitScope() { return limitScope; }
    public BigDecimal getPreviousLimit() { return previousLimit; }
    public BigDecimal getNewLimit() { return newLimit; }
    public String getCountryCode() { return countryCode; }
    public String getRequestFingerprint() { return requestFingerprint; }
}
