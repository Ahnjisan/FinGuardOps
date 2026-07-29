package com.aifds.backend.transaction.dto;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.service.TransactionIntakeSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionCreateResponse(
        UUID transactionId,
        TransactionProcessingStatus processingStatus,
        String riskLevel,
        String riskResponseOutcome,
        String adoptedDetectionResultId,
        String caseId,
        Instant createdAt,
        String traceId
) {

    public TransactionCreateResponse {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(
                processingStatus,
                "processingStatus must not be null"
        );
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public static TransactionCreateResponse from(
            TransactionIntakeSnapshot snapshot,
            String traceId
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return new TransactionCreateResponse(
                snapshot.transactionId(),
                snapshot.processingStatus(),
                snapshot.riskLevel(),
                snapshot.riskResponseOutcome(),
                snapshot.adoptedDetectionResultId(),
                snapshot.caseId(),
                snapshot.createdAt(),
                traceId
        );
    }
}
