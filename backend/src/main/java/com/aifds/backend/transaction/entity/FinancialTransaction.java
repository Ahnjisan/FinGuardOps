package com.aifds.backend.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "financial_transaction")
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "external_customer_ref", nullable = false, length = 128)
    private String externalCustomerRef;

    @Column(name = "sender_account_ref", nullable = false, length = 128)
    private String senderAccountRef;

    @Column(name = "recipient_account_ref", length = 128)
    private String recipientAccountRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private TransactionChannel channel;

    @Column(name = "device_ref", length = 128)
    private String deviceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private TransactionProcessingStatus processingStatus;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancialTransaction() {
    }

    public FinancialTransaction(
            UUID transactionId,
            TransactionType transactionType,
            BigDecimal amount,
            String currencyCode,
            Instant occurredAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            TransactionChannel channel,
            String deviceRef
    ) {
        this.transactionId = transactionId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.occurredAt = occurredAt;
        this.externalCustomerRef = externalCustomerRef;
        this.senderAccountRef = senderAccountRef;
        this.recipientAccountRef = recipientAccountRef;
        this.channel = channel;
        this.deviceRef = deviceRef;
        this.processingStatus = TransactionProcessingStatus.RECEIVED;
    }

    @PrePersist
    private void initializeTimestamps() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getExternalCustomerRef() {
        return externalCustomerRef;
    }

    public String getSenderAccountRef() {
        return senderAccountRef;
    }

    public String getRecipientAccountRef() {
        return recipientAccountRef;
    }

    public TransactionChannel getChannel() {
        return channel;
    }

    public String getDeviceRef() {
        return deviceRef;
    }

    public TransactionProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
