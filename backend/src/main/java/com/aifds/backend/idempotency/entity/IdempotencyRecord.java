package com.aifds.backend.idempotency.entity;

import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
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
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    private static final Pattern FAILURE_CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

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

    @Generated(event = EventType.INSERT)
    @Column(
            name = "expires_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant expiresAt;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint
    ) {
        this.operationScope = Objects.requireNonNull(
                operationScope,
                "operationScope must not be null"
        );
        this.idempotencyKey = Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null"
        );
        this.requestFingerprint = Objects.requireNonNull(
                requestFingerprint,
                "requestFingerprint must not be null"
        );
        this.processingStatus = IdempotencyProcessingStatus.IN_PROGRESS;
    }

    public static IdempotencyRecord inProgress(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint
    ) {
        return new IdempotencyRecord(
                operationScope,
                idempotencyKey,
                requestFingerprint
        );
    }

    public void complete(
            FinancialTransaction financialTransaction,
            JsonNode responseSnapshot,
            Instant finishedAt
    ) {
        requireInProgress(IdempotencyProcessingStatus.COMPLETED);
        requireCompatibleTransaction(financialTransaction);
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (responseSnapshot == null || !responseSnapshot.isObject()) {
            throw new IllegalArgumentException("responseSnapshot must be a JSON object");
        }
        if (containsTraceId(responseSnapshot)) {
            throw new IllegalArgumentException("responseSnapshot must not contain traceId");
        }

        this.processingStatus = IdempotencyProcessingStatus.COMPLETED;
        if (this.financialTransaction == null) {
            this.financialTransaction = financialTransaction;
        }
        this.responseSnapshot = responseSnapshot.deepCopy();
        this.failureCode = null;
        this.finishedAt = finishedAt;
    }

    public void linkTransaction(FinancialTransaction financialTransaction) {
        requireInProgress(IdempotencyProcessingStatus.IN_PROGRESS);
        requireCompatibleTransaction(financialTransaction);
        if (this.financialTransaction == null) {
            this.financialTransaction = financialTransaction;
        }
    }

    public void fail(String failureCode, Instant finishedAt) {
        requireInProgress(IdempotencyProcessingStatus.FAILED);
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        requireValidFailureCode(failureCode);

        this.processingStatus = IdempotencyProcessingStatus.FAILED;
        this.responseSnapshot = null;
        this.failureCode = failureCode;
        this.finishedAt = finishedAt;
    }

    public void fail(
            String failureCode,
            JsonNode failureSnapshot,
            Instant finishedAt
    ) {
        requireInProgress(IdempotencyProcessingStatus.FAILED);
        requireValidFailureCode(failureCode);
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (financialTransaction == null) {
            throw new IllegalStateException(
                    "Typed failure requires a linked financial transaction"
            );
        }
        if (failureSnapshot == null || !failureSnapshot.isObject()) {
            throw new IllegalArgumentException(
                    "failureSnapshot must be a JSON object"
            );
        }
        if (containsTraceId(failureSnapshot)) {
            throw new IllegalArgumentException(
                    "failureSnapshot must not contain traceId"
            );
        }

        this.processingStatus = IdempotencyProcessingStatus.FAILED;
        this.responseSnapshot = failureSnapshot.deepCopy();
        this.failureCode = failureCode;
        this.finishedAt = finishedAt;
    }

    private void requireValidFailureCode(String failureCode) {
        if (failureCode == null
                || !FAILURE_CODE_PATTERN.matcher(failureCode).matches()) {
            throw new IllegalArgumentException(
                    "failureCode must match ^[A-Z][A-Z0-9_]{0,63}$"
            );
        }
    }

    private void requireInProgress(IdempotencyProcessingStatus targetStatus) {
        if (processingStatus != IdempotencyProcessingStatus.IN_PROGRESS) {
            throw new IdempotencyStateTransitionNotAllowedException(
                    processingStatus,
                    targetStatus
            );
        }
    }

    private void requireCompatibleTransaction(
            FinancialTransaction candidateTransaction
    ) {
        Objects.requireNonNull(
                candidateTransaction,
                "financialTransaction must not be null"
        );
        UUID candidateTransactionId = Objects.requireNonNull(
                candidateTransaction.getTransactionId(),
                "financialTransaction.transactionId must not be null"
        );
        if (financialTransaction == null) {
            return;
        }

        UUID linkedTransactionId = financialTransaction.getTransactionId();
        if (linkedTransactionId == null
                || !linkedTransactionId.equals(candidateTransactionId)) {
            throw new IllegalStateException(
                    "Idempotency record is already linked to a different transaction"
            );
        }
    }

    private boolean containsTraceId(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if ("traceId".equals(field.getKey()) || containsTraceId(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsTraceId(element)) {
                    return true;
                }
            }
        }
        return false;
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
        return responseSnapshot == null ? null : responseSnapshot.deepCopy();
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
