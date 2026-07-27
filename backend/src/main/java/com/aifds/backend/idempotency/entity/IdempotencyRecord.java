package com.aifds.backend.idempotency.entity;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "operation_scope", nullable = false, length = 64, updatable = false)
    private String operationScope;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private IdempotencyProcessingStatus processingStatus;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "financial_transaction_id",
            unique = true,
            foreignKey = @ForeignKey(name = "fk_idempotency_record_transaction")
    )
    private FinancialTransaction financialTransaction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", columnDefinition = "jsonb")
    private JsonNode responseSnapshot;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint,
            IdempotencyProcessingStatus processingStatus,
            FinancialTransaction financialTransaction,
            JsonNode responseSnapshot,
            String failureCode,
            Instant finishedAt
    ) {
        this.operationScope = operationScope;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.processingStatus = processingStatus;
        this.financialTransaction = financialTransaction;
        this.responseSnapshot = responseSnapshot;
        this.failureCode = failureCode;
        this.finishedAt = finishedAt;
    }

    public static IdempotencyRecord inProgress(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint,
            FinancialTransaction financialTransaction
    ) {
        return new IdempotencyRecord(
                operationScope,
                idempotencyKey,
                requestFingerprint,
                IdempotencyProcessingStatus.IN_PROGRESS,
                financialTransaction,
                null,
                null,
                null
        );
    }

    public static IdempotencyRecord completed(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint,
            FinancialTransaction financialTransaction,
            JsonNode responseSnapshot
    ) {
        return new IdempotencyRecord(
                operationScope,
                idempotencyKey,
                requestFingerprint,
                IdempotencyProcessingStatus.COMPLETED,
                financialTransaction,
                responseSnapshot,
                null,
                Instant.now()
        );
    }

    @PrePersist
    private void initializeTimestamps() {
        Instant now = finishedAt == null ? Instant.now() : finishedAt;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = now.plus(24, ChronoUnit.HOURS);
    }

    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOperationScope() {
        return operationScope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public IdempotencyProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public FinancialTransaction getFinancialTransaction() {
        return financialTransaction;
    }

    public JsonNode getResponseSnapshot() {
        return responseSnapshot;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
