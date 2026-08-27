package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.service.ExternalRiskFailureSnapshotService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.transaction.validation.TransactionRequestValidator;
import org.springframework.stereotype.Service;

@Service
public class TransactionIntakeService {

    static final String DUPLICATE_TRANSACTION =
            "DUPLICATE_TRANSACTION";
    static final String TRANSACTION_INTAKE_FAILED =
            "TRANSACTION_INTAKE_FAILED";

    private final IdempotencyKeyValidator idempotencyKeyValidator;
    private final TransactionRequestValidator transactionRequestValidator;
    private final TransactionRequestFingerprint transactionRequestFingerprint;
    private final IdempotencyService idempotencyService;
    private final TransactionSynchronousProcessingCoordinator coordinator;
    private final TransactionIntakeSnapshotCodec snapshotCodec;
    private final ExternalRiskFailureSnapshotService failureSnapshotService;

    public TransactionIntakeService(
            IdempotencyKeyValidator idempotencyKeyValidator,
            TransactionRequestValidator transactionRequestValidator,
            TransactionRequestFingerprint transactionRequestFingerprint,
            IdempotencyService idempotencyService,
            TransactionSynchronousProcessingCoordinator coordinator,
            TransactionIntakeSnapshotCodec snapshotCodec,
            ExternalRiskFailureSnapshotService failureSnapshotService
    ) {
        this.idempotencyKeyValidator = idempotencyKeyValidator;
        this.transactionRequestValidator = transactionRequestValidator;
        this.transactionRequestFingerprint = transactionRequestFingerprint;
        this.idempotencyService = idempotencyService;
        this.coordinator = coordinator;
        this.snapshotCodec = snapshotCodec;
        this.failureSnapshotService = failureSnapshotService;
    }

    public TransactionIntakeResult receive(
            String idempotencyKey,
            TransactionCreateRequest request,
            String traceId
    ) {
        String validatedIdempotencyKey =
                idempotencyKeyValidator.validate(idempotencyKey);
        ValidatedTransactionCommand command =
                transactionRequestValidator.validate(request);
        transactionRequestFingerprint.calculate(command.toFingerprintInput());
        if (!coordinator.isAvailable()) {
            return new TransactionIntakeResult.ProviderUnavailable();
        }
        IdempotencyClaimResult claimResult = idempotencyService.claim(
                validatedIdempotencyKey,
                command.toFingerprintInput()
        );

        if (claimResult instanceof IdempotencyClaimResult.Acquired acquired) {
            return coordinator.process(
                    acquired.recordId(),
                    command,
                    traceId
            );
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
        if (claimResult
                instanceof IdempotencyClaimResult.FailedWithSnapshot failed) {
            ExternalRiskFailureSnapshot snapshot =
                    failureSnapshotService.decode(failed);
            return new TransactionIntakeResult.ExternalRiskFailureReplay(
                    snapshot.httpStatus(),
                    snapshot.responseBody().code(),
                    snapshot.responseBody().message()
            );
        }
        throw new IllegalStateException(
                "Unsupported idempotency claim result: "
                        + claimResult.getClass().getName()
        );
    }

}
