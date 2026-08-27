package com.aifds.backend.idempotency.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

@Service
public class IdempotencyService {

    static final String TRANSACTION_CREATE_OPERATION_SCOPE =
            "POST:/api/v1/transactions";
    private static final String POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String SCOPE_KEY_UNIQUE_CONSTRAINT =
            "uq_idempotency_record_scope_key";

    private final IdempotencyClaimWriter idempotencyClaimWriter;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final FinancialTransactionRepository financialTransactionRepository;
    private final TransactionRequestFingerprint transactionRequestFingerprint;
    private final DatabaseTransactionTimestampProvider timestampProvider;

    public IdempotencyService(
            IdempotencyClaimWriter idempotencyClaimWriter,
            IdempotencyRecordRepository idempotencyRecordRepository,
            FinancialTransactionRepository financialTransactionRepository,
            TransactionRequestFingerprint transactionRequestFingerprint,
            DatabaseTransactionTimestampProvider timestampProvider
    ) {
        this.idempotencyClaimWriter = idempotencyClaimWriter;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.financialTransactionRepository = financialTransactionRepository;
        this.transactionRequestFingerprint = transactionRequestFingerprint;
        this.timestampProvider = timestampProvider;
    }

    public IdempotencyClaimResult claim(
            String idempotencyKey,
            TransactionFingerprintInput input
    ) {
        String requestFingerprint = transactionRequestFingerprint.calculate(input);

        try {
            long recordId = idempotencyClaimWriter.createInProgress(
                    TRANSACTION_CREATE_OPERATION_SCOPE,
                    idempotencyKey,
                    requestFingerprint
            );
            return new IdempotencyClaimResult.Acquired(recordId);
        } catch (DataIntegrityViolationException exception) {
            if (!isScopeKeyUniqueViolation(exception)) {
                throw exception;
            }
        }

        IdempotencyRecord existing = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(
                        TRANSACTION_CREATE_OPERATION_SCOPE,
                        idempotencyKey
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Scope-key unique violation occurred but existing record was not found"
                ));

        if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
            return new IdempotencyClaimResult.KeyConflict();
        }

        return switch (existing.getProcessingStatus()) {
            case IN_PROGRESS -> new IdempotencyClaimResult.InProgress();
            case COMPLETED -> new IdempotencyClaimResult.Completed(
                    existing.getResponseSnapshot().toString()
            );
            case FAILED -> failedResult(existing);
        };
    }

    @Transactional
    public IdempotencyClaimResult.Completed complete(
            long recordId,
            UUID transactionId,
            JsonNode responseSnapshot,
            Instant finishedAt
    ) {
        FinancialTransaction financialTransaction = financialTransactionRepository
                .findByTransactionId(transactionId)
                .orElseThrow(
                        () -> new IdempotencyCompletionTransactionNotFoundException(
                                transactionId
                        )
                );
        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(recordId)
                .orElseThrow(() -> new IdempotencyRecordNotFoundException(recordId));

        record.complete(financialTransaction, responseSnapshot, finishedAt);

        return new IdempotencyClaimResult.Completed(
                record.getResponseSnapshot().toString()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyClaimResult.Failed fail(long recordId, String failureCode) {
        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(recordId)
                .orElseThrow(() -> new IdempotencyRecordNotFoundException(recordId));

        record.fail(
                failureCode,
                timestampProvider.currentTransactionTimestamp()
        );

        return new IdempotencyClaimResult.Failed(record.getFailureCode());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyClaimResult.FailedWithSnapshot failWithSnapshot(
            long recordId,
            String failureCode,
            Function<Instant, JsonNode> snapshotFactory
    ) {
        if (snapshotFactory == null) {
            throw new NullPointerException("snapshotFactory must not be null");
        }
        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(recordId)
                .orElseThrow(() -> new IdempotencyRecordNotFoundException(recordId));
        Instant finishedAt = timestampProvider.currentTransactionTimestamp();
        JsonNode failureSnapshot = snapshotFactory.apply(finishedAt);

        record.fail(failureCode, failureSnapshot, finishedAt);

        return new IdempotencyClaimResult.FailedWithSnapshot(
                record.getFailureCode(),
                record.getResponseSnapshot().toString(),
                record.getFinishedAt()
        );
    }

    private IdempotencyClaimResult failedResult(IdempotencyRecord record) {
        JsonNode snapshot = record.getResponseSnapshot();
        if (snapshot == null) {
            return new IdempotencyClaimResult.Failed(record.getFailureCode());
        }
        return new IdempotencyClaimResult.FailedWithSnapshot(
                record.getFailureCode(),
                snapshot.toString(),
                record.getFinishedAt()
        );
    }

    private boolean isScopeKeyUniqueViolation(DataIntegrityViolationException exception) {
        boolean uniqueSqlState = false;
        boolean targetConstraint = false;
        Throwable current = exception;

        while (current != null) {
            if (current instanceof SQLException sqlException
                    && POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE.equals(
                    sqlException.getSQLState()
            )) {
                uniqueSqlState = true;
            }
            if (current.getMessage() != null
                    && current.getMessage().contains(SCOPE_KEY_UNIQUE_CONSTRAINT)) {
                targetConstraint = true;
            }
            current = current.getCause();
        }

        return uniqueSqlState && targetConstraint;
    }
}
