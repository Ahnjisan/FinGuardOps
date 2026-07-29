package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionIntakeCompletionService {

    private final TransactionIntakeWriter transactionIntakeWriter;
    private final TransactionIntakeSnapshotCodec snapshotCodec;
    private final IdempotencyService idempotencyService;

    public TransactionIntakeCompletionService(
            TransactionIntakeWriter transactionIntakeWriter,
            TransactionIntakeSnapshotCodec snapshotCodec,
            IdempotencyService idempotencyService
    ) {
        this.transactionIntakeWriter = transactionIntakeWriter;
        this.snapshotCodec = snapshotCodec;
        this.idempotencyService = idempotencyService;
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
        JsonNode encodedSnapshot = snapshotCodec.encode(snapshot);
        idempotencyService.complete(
                idempotencyRecordId,
                snapshot.transactionId(),
                encodedSnapshot
        );
        return new TransactionIntakeResult.Received(snapshot);
    }
}
