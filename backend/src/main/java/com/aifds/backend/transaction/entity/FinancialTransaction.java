package com.aifds.backend.transaction.entity;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "adopted_detection_result_id",
            foreignKey = @ForeignKey(
                    name =
                            "fk_financial_transaction_adopted_detection_result"
            )
    )
    private DetectionResult adoptedDetectionResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_response_outcome", length = 32)
    private RiskResponseOutcome riskResponseOutcome;

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

    public void adoptDetectionResult(DetectionResult detectionResult) {
        Objects.requireNonNull(
                detectionResult,
                "detectionResult must not be null"
        );
        if (!detectionResult.belongsTo(this)) {
            throw new IllegalArgumentException(
                    "Detection result must belong to this transaction"
            );
        }
        if (detectionResult.getAnalysisStatus()
                != DetectionAnalysisStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Only completed detection results can be adopted"
            );
        }

        this.adoptedDetectionResult = detectionResult;
        this.riskLevel = detectionResult.getRiskLevel();
        this.riskResponseOutcome = null;
    }

    public void applyRiskResponseOutcome(RiskResponseOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (adoptedDetectionResult == null || riskLevel == null) {
            throw new IllegalStateException(
                    "A completed detection result must be adopted first"
            );
        }
        if (!outcome.supports(riskLevel)) {
            throw new IllegalArgumentException(
                    "Risk response outcome does not match risk level"
            );
        }
        this.riskResponseOutcome = outcome;
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

    public DetectionResult getAdoptedDetectionResult() {
        return adoptedDetectionResult;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public RiskResponseOutcome getRiskResponseOutcome() {
        return riskResponseOutcome;
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
