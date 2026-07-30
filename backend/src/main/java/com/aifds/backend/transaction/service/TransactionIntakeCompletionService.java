package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class TransactionIntakeCompletionService {

    private final TransactionIntakeWriter transactionIntakeWriter;
    private final TransactionIntakeSnapshotCodec snapshotCodec;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public TransactionIntakeCompletionService(
            TransactionIntakeWriter transactionIntakeWriter,
            TransactionIntakeSnapshotCodec snapshotCodec,
            IdempotencyService idempotencyService,
            Clock clock
    ) {
        this.transactionIntakeWriter = transactionIntakeWriter;
        this.snapshotCodec = snapshotCodec;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
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
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
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
