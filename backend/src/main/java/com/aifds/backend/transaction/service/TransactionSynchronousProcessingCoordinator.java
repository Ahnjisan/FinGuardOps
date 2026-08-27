package com.aifds.backend.transaction.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.service.ExternalRiskFailureSnapshotService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

@Service
public class TransactionSynchronousProcessingCoordinator {

    static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

    private static final String POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String TRANSACTION_ID_UNIQUE_CONSTRAINT =
            "uq_financial_transaction_transaction_id";

    private final ObjectProvider<ExternalRiskRuleAnalysisCoordinator>
            analysisCoordinatorProvider;
    private final TransactionIntakeWriter transactionIntakeWriter;
    private final ExternalRiskFailureSnapshotService failureSnapshotService;
    private final TransactionProcessingFailureStateReader failureStateReader;
    private final IdempotencyService idempotencyService;
    private final RiskResponseFinalizationService finalizationService;
    private final TransactionIntakeCompletionService completionService;

    public TransactionSynchronousProcessingCoordinator(
            ObjectProvider<ExternalRiskRuleAnalysisCoordinator>
                    analysisCoordinatorProvider,
            TransactionIntakeWriter transactionIntakeWriter,
            ExternalRiskFailureSnapshotService failureSnapshotService,
            TransactionProcessingFailureStateReader failureStateReader,
            IdempotencyService idempotencyService,
            RiskResponseFinalizationService finalizationService,
            TransactionIntakeCompletionService completionService
    ) {
        this.analysisCoordinatorProvider = analysisCoordinatorProvider;
        this.transactionIntakeWriter = transactionIntakeWriter;
        this.failureSnapshotService = failureSnapshotService;
        this.failureStateReader = failureStateReader;
        this.idempotencyService = idempotencyService;
        this.finalizationService = finalizationService;
        this.completionService = completionService;
    }

    public boolean isAvailable() {
        return analysisCoordinatorProvider.getIfAvailable() != null;
    }

    public TransactionIntakeResult process(
            long idempotencyRecordId,
            ValidatedTransactionCommand command,
            String traceId
    ) {
        ExternalRiskRuleAnalysisCoordinator analysisCoordinator =
                analysisCoordinatorProvider.getIfAvailable();
        if (analysisCoordinator == null) {
            return new TransactionIntakeResult.ProviderUnavailable();
        }

        final PersistedTransactionIntake persisted;
        try {
            persisted = persistReceived(idempotencyRecordId, command);
        } catch (DuplicateTransactionSignal duplicate) {
            return new TransactionIntakeResult.DuplicateTransaction(
                    duplicate.command().transactionId()
            );
        }
        requireNoActiveTransaction();
        final ExternalRiskSnapshot externalRiskSnapshot;
        try {
            externalRiskSnapshot = analysisCoordinator.lookupExternalRisk(
                    persisted.transactionId(),
                    traceId
            );
        } catch (ExternalRiskLookupException original) {
            return persistExternalRiskFailure(idempotencyRecordId, original);
        }

        requireNoActiveTransaction();
        try {
            analysisCoordinator.analyzeWithExternalRiskSnapshot(
                    persisted.transactionId(),
                    traceId,
                    externalRiskSnapshot
            );
        } catch (RuntimeException original) {
            return handleRuleFailure(
                    idempotencyRecordId,
                    persisted,
                    original
            );
        }

        requireNoActiveTransaction();
        RiskResponseFinalizationResult finalized =
                finalizationService.finalizeRiskResponse(
                        persisted.transactionId()
                );
        requireNoActiveTransaction();
        return completionService.complete(
                idempotencyRecordId,
                finalized,
                persisted.createdAt()
        );
    }

    private PersistedTransactionIntake persistReceived(
            long idempotencyRecordId,
            ValidatedTransactionCommand command
    ) {
        try {
            return transactionIntakeWriter.saveAndLink(
                    idempotencyRecordId,
                    command
            );
        } catch (RuntimeException writerException) {
            boolean duplicateTransaction =
                    isDuplicateTransactionIdViolation(writerException);
            String failureCode = duplicateTransaction
                    ? TransactionIntakeService.DUPLICATE_TRANSACTION
                    : TransactionIntakeService.TRANSACTION_INTAKE_FAILED;
            failPreservingOriginal(
                    idempotencyRecordId,
                    failureCode,
                    writerException
            );
            if (duplicateTransaction) {
                throw new DuplicateTransactionSignal(
                        command,
                        writerException
                );
            }
            throw writerException;
        }
    }

    private TransactionIntakeResult persistExternalRiskFailure(
            long idempotencyRecordId,
            ExternalRiskLookupException original
    ) {
        try {
            ExternalRiskFailureSnapshot snapshot = failureSnapshotService
                    .persist(idempotencyRecordId, original);
            return new TransactionIntakeResult.ExternalRiskFailure(
                    snapshot.httpStatus(),
                    snapshot.responseBody().code(),
                    snapshot.responseBody().message()
            );
        } catch (RuntimeException writerException) {
            addSuppressed(original, writerException);
            throw original;
        }
    }

    private TransactionIntakeResult handleRuleFailure(
            long idempotencyRecordId,
            PersistedTransactionIntake persisted,
            RuntimeException original
    ) {
        final TransactionProcessingFailureStateReader.FailureState state;
        try {
            state = failureStateReader.read(persisted.transactionId());
        } catch (RuntimeException readerException) {
            addSuppressed(original, readerException);
            throw original;
        }
        if (state
                != TransactionProcessingFailureStateReader.FailureState
                .CONFIRMED_FAILURE) {
            throw original;
        }
        try {
            idempotencyService.fail(
                    idempotencyRecordId,
                    DEPENDENCY_UNAVAILABLE
            );
        } catch (RuntimeException writerException) {
            addSuppressed(original, writerException);
            throw original;
        }
        return new TransactionIntakeResult.RuleFailure();
    }

    private void failPreservingOriginal(
            long idempotencyRecordId,
            String failureCode,
            RuntimeException original
    ) {
        try {
            idempotencyService.fail(idempotencyRecordId, failureCode);
        } catch (RuntimeException failureTransitionException) {
            addSuppressed(original, failureTransitionException);
            throw original;
        }
    }

    private void addSuppressed(
            RuntimeException original,
            RuntimeException secondary
    ) {
        if (secondary != original) {
            original.addSuppressed(secondary);
        }
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Synchronous transaction processing requires no active transaction"
            );
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

    static final class DuplicateTransactionSignal extends RuntimeException {

        private final ValidatedTransactionCommand command;

        DuplicateTransactionSignal(
                ValidatedTransactionCommand command,
                RuntimeException cause
        ) {
            super("Duplicate transaction", cause);
            this.command = command;
        }

        ValidatedTransactionCommand command() {
            return command;
        }
    }
}
