package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.transaction.validation.TransactionRequestValidator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

@Service
public class TransactionIntakeService {

    static final String DUPLICATE_TRANSACTION =
            "DUPLICATE_TRANSACTION";
    static final String TRANSACTION_INTAKE_FAILED =
            "TRANSACTION_INTAKE_FAILED";

    private static final String POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String TRANSACTION_ID_UNIQUE_CONSTRAINT =
            "uq_financial_transaction_transaction_id";

    private final IdempotencyKeyValidator idempotencyKeyValidator;
    private final TransactionRequestValidator transactionRequestValidator;
    private final IdempotencyService idempotencyService;
    private final TransactionIntakeCompletionService completionService;
    private final TransactionIntakeSnapshotCodec snapshotCodec;

    public TransactionIntakeService(
            IdempotencyKeyValidator idempotencyKeyValidator,
            TransactionRequestValidator transactionRequestValidator,
            IdempotencyService idempotencyService,
            TransactionIntakeCompletionService completionService,
            TransactionIntakeSnapshotCodec snapshotCodec
    ) {
        this.idempotencyKeyValidator = idempotencyKeyValidator;
        this.transactionRequestValidator = transactionRequestValidator;
        this.idempotencyService = idempotencyService;
        this.completionService = completionService;
        this.snapshotCodec = snapshotCodec;
    }

    public TransactionIntakeResult receive(
            String idempotencyKey,
            TransactionCreateRequest request
    ) {
        String validatedIdempotencyKey =
                idempotencyKeyValidator.validate(idempotencyKey);
        ValidatedTransactionCommand command =
                transactionRequestValidator.validate(request);
        IdempotencyClaimResult claimResult = idempotencyService.claim(
                validatedIdempotencyKey,
                command.toFingerprintInput()
        );

        if (claimResult instanceof IdempotencyClaimResult.Acquired acquired) {
            return persistAcquired(acquired.recordId(), command);
        }
        if (claimResult instanceof IdempotencyClaimResult.KeyConflict) {
            return new TransactionIntakeResult.KeyConflict();
        }
        if (claimResult instanceof IdempotencyClaimResult.InProgress) {
            return new TransactionIntakeResult.InProgress();
        }
        if (claimResult instanceof IdempotencyClaimResult.Completed completed) {
            TransactionIntakeSnapshotReplay replay =
                    snapshotCodec.decode(completed.responseSnapshotJson());
            return new TransactionIntakeResult.CompletedReplay(
                    replay.snapshot(),
                    replay.httpStatus()
            );
        }
        if (claimResult instanceof IdempotencyClaimResult.Failed failed) {
            return new TransactionIntakeResult.PreviousFailure(
                    failed.failureCode()
            );
        }
        throw new IllegalStateException(
                "Unsupported idempotency claim result: "
                        + claimResult.getClass().getName()
        );
    }

    private TransactionIntakeResult persistAcquired(
            long idempotencyRecordId,
            ValidatedTransactionCommand command
    ) {
        try {
            return completionService.complete(
                    idempotencyRecordId,
                    command
            );
        } catch (RuntimeException writerException) {
            boolean duplicateTransaction =
                    isDuplicateTransactionIdViolation(writerException);
            String failureCode = duplicateTransaction
                    ? DUPLICATE_TRANSACTION
                    : TRANSACTION_INTAKE_FAILED;

            try {
                idempotencyService.fail(idempotencyRecordId, failureCode);
            } catch (RuntimeException failureTransitionException) {
                writerException.addSuppressed(failureTransitionException);
                throw writerException;
            }

            if (duplicateTransaction) {
                return new TransactionIntakeResult.DuplicateTransaction(
                        command.transactionId()
                );
            }
            throw writerException;
        }
    }

    private boolean isDuplicateTransactionIdViolation(
            RuntimeException exception
    ) {
        if (!(exception instanceof DataIntegrityViolationException)) {
            return false;
        }

        boolean uniqueViolation = false;
        boolean transactionIdConstraint = false;
        Throwable current = exception;

        while (current != null) {
            if (current instanceof SQLException sqlException
                    && POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE.equals(
                    sqlException.getSQLState()
            )) {
                uniqueViolation = true;
            }
            if (current instanceof ConstraintViolationException violation
                    && TRANSACTION_ID_UNIQUE_CONSTRAINT.equals(
                    violation.getConstraintName()
            )) {
                transactionIdConstraint = true;
            }
            current = current.getCause();
        }

        return uniqueViolation && transactionIdConstraint;
    }
}
