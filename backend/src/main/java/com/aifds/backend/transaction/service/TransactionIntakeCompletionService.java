package com.aifds.backend.transaction.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TransactionIntakeCompletionService {

    private final TransactionIntakeSnapshotCodec snapshotCodec;
    private final IdempotencyService idempotencyService;
    private final DatabaseTransactionTimestampProvider timestampProvider;

    public TransactionIntakeCompletionService(
            TransactionIntakeSnapshotCodec snapshotCodec,
            IdempotencyService idempotencyService,
            DatabaseTransactionTimestampProvider timestampProvider
    ) {
        this.snapshotCodec = snapshotCodec;
        this.idempotencyService = idempotencyService;
        this.timestampProvider = timestampProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionIntakeResult.Received complete(
            long idempotencyRecordId,
            RiskResponseFinalizationResult finalizationResult,
            Instant createdAt
    ) {
        Instant finalizedAt = timestampProvider.currentTransactionTimestamp();
        TransactionFinalResponseSnapshot finalSnapshot =
                new TransactionFinalResponseSnapshot(
                        finalizationResult,
                        createdAt
                );
        int httpStatus = TransactionIntakeSnapshotEnvelopeV2Codec
                .SUPPORTED_HTTP_STATUS;
        JsonNode encodedSnapshot = snapshotCodec.encodeV2(
                finalSnapshot,
                httpStatus,
                finalizedAt
        );
        idempotencyService.complete(
                idempotencyRecordId,
                finalSnapshot.transactionId(),
                encodedSnapshot,
                finalizedAt
        );
        return new TransactionIntakeResult.Received(
                finalSnapshot.toTransactionIntakeSnapshot(),
                httpStatus
        );
    }
}
