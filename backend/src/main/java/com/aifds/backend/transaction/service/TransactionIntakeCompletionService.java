package com.aifds.backend.transaction.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TransactionIntakeCompletionService {

    private final TransactionIntakeWriter transactionIntakeWriter;
    private final TransactionIntakeSnapshotCodec snapshotCodec;
    private final IdempotencyService idempotencyService;
    private final DatabaseTransactionTimestampProvider timestampProvider;

    public TransactionIntakeCompletionService(
            TransactionIntakeWriter transactionIntakeWriter,
            TransactionIntakeSnapshotCodec snapshotCodec,
            IdempotencyService idempotencyService,
            DatabaseTransactionTimestampProvider timestampProvider
    ) {
        this.transactionIntakeWriter = transactionIntakeWriter;
        this.snapshotCodec = snapshotCodec;
        this.idempotencyService = idempotencyService;
        this.timestampProvider = timestampProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionIntakeResult.Received complete(
            long idempotencyRecordId,
            ValidatedTransactionCommand command
    ) {
        PersistedTransactionIntake persisted =
                transactionIntakeWriter.saveAndLink(
                        idempotencyRecordId,
                        command
                );
        TransactionIntakeSnapshot snapshot =
                TransactionIntakeSnapshot.received(persisted);
        Instant finalizedAt = timestampProvider.currentTransactionTimestamp();
        int httpStatus =
                TransactionIntakeSnapshotEnvelopeCodec.SUPPORTED_HTTP_STATUS;
        JsonNode encodedSnapshot = snapshotCodec.encode(
                snapshot,
                httpStatus,
                finalizedAt
        );
        idempotencyService.complete(
                idempotencyRecordId,
                snapshot.transactionId(),
                encodedSnapshot,
                finalizedAt
        );
        return new TransactionIntakeResult.Received(snapshot, httpStatus);
    }
}
