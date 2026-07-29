package com.aifds.backend.behavior.entity;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "behavior_event")
public class BehaviorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32, updatable = false)
    private BehaviorEventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(
            name = "external_customer_ref",
            nullable = false,
            length = 128,
            updatable = false
    )
    private String externalCustomerRef;

    @Column(name = "account_ref", length = 128, updatable = false)
    private String accountRef;

    @Column(name = "device_ref", length = 128, updatable = false)
    private String deviceRef;

    @Column(name = "beneficiary_ref", length = 128, updatable = false)
    private String beneficiaryRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "financial_transaction_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_behavior_event_transaction")
    )
    private FinancialTransaction financialTransaction;

    @Column(
            name = "request_fingerprint",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BehaviorEvent() {
    }

    public BehaviorEvent(
            UUID eventId,
            BehaviorEventType eventType,
            Instant occurredAt,
            String externalCustomerRef,
            String accountRef,
            String deviceRef,
            String beneficiaryRef,
            FinancialTransaction financialTransaction,
            String requestFingerprint
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(
                eventType,
                "eventType must not be null"
        );
        this.occurredAt = Objects.requireNonNull(
                occurredAt,
                "occurredAt must not be null"
        );
        this.externalCustomerRef = Objects.requireNonNull(
                externalCustomerRef,
                "externalCustomerRef must not be null"
        );
        this.accountRef = accountRef;
        this.deviceRef = deviceRef;
        this.beneficiaryRef = beneficiaryRef;
        this.financialTransaction = financialTransaction;
        this.requestFingerprint = Objects.requireNonNull(
                requestFingerprint,
                "requestFingerprint must not be null"
        );
    }

    @PrePersist
    private void initializeCreatedAt() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public BehaviorEventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getExternalCustomerRef() {
        return externalCustomerRef;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public String getDeviceRef() {
        return deviceRef;
    }

    public String getBeneficiaryRef() {
        return beneficiaryRef;
    }

    public FinancialTransaction getFinancialTransaction() {
        return financialTransaction;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
