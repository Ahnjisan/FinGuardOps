package com.aifds.backend.externalrisk.client.dto;

import com.aifds.backend.transaction.entity.TransactionType;

import java.time.Instant;

public record ExternalRiskHttpRequest(
        TransactionType transactionType,
        Instant evaluationCutoffAt,
        String externalCustomerRef,
        String senderAccountRef,
        String recipientAccountRef,
        String deviceRef,
        String traceId
) {

    @Override
    public String toString() {
        return "ExternalRiskHttpRequest[transactionType=" + transactionType
                + ", evaluationCutoffAt=" + evaluationCutoffAt
                + ", recipientReferencePresent="
                + (recipientAccountRef != null)
                + ", deviceReferencePresent=" + (deviceRef != null)
                + ", sensitiveFields=REDACTED]";
    }
}
