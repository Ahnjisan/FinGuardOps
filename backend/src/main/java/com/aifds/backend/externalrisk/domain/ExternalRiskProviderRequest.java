package com.aifds.backend.externalrisk.domain;

import com.aifds.backend.transaction.entity.TransactionType;

import java.time.Instant;

public record ExternalRiskProviderRequest(
        TransactionType transactionType,
        Instant evaluationCutoffAt,
        String externalCustomerRef,
        String senderAccountRef,
        String recipientAccountRef,
        String deviceRef,
        String traceId
) {

    public ExternalRiskProviderRequest {
        if (transactionType == null
                || !ExternalRiskContracts.isMicrosecondInstant(evaluationCutoffAt)
                || !ExternalRiskContracts.isReference(externalCustomerRef)
                || !ExternalRiskContracts.isReference(senderAccountRef)
                || !ExternalRiskContracts.isOptionalReference(recipientAccountRef)
                || !ExternalRiskContracts.isOptionalReference(deviceRef)
                || !ExternalRiskContracts.isTraceId(traceId)) {
            throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.INVALID_REQUEST
            );
        }
    }

    @Override
    public String toString() {
        return "ExternalRiskProviderRequest[transactionType="
                + transactionType
                + ", evaluationCutoffAt="
                + evaluationCutoffAt
                + ", recipientReferencePresent="
                + (recipientAccountRef != null)
                + ", deviceReferencePresent="
                + (deviceRef != null)
                + ", sensitiveFields=REDACTED]";
    }
}
