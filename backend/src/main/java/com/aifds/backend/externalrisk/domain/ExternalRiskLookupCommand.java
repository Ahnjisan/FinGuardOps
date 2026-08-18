package com.aifds.backend.externalrisk.domain;

import com.aifds.backend.transaction.entity.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record ExternalRiskLookupCommand(
        UUID transactionId,
        TransactionType transactionType,
        Instant evaluationCutoffAt,
        String externalCustomerRef,
        String senderAccountRef,
        String recipientAccountRef,
        String deviceRef,
        String traceId
) {

    public ExternalRiskLookupCommand {
        validate(
                transactionId,
                transactionType,
                evaluationCutoffAt,
                externalCustomerRef,
                senderAccountRef,
                recipientAccountRef,
                deviceRef,
                traceId
        );
    }

    public void validate() {
        validate(
                transactionId,
                transactionType,
                evaluationCutoffAt,
                externalCustomerRef,
                senderAccountRef,
                recipientAccountRef,
                deviceRef,
                traceId
        );
    }

    @Override
    public String toString() {
        return "ExternalRiskLookupCommand[transactionType="
                + transactionType
                + ", evaluationCutoffAt="
                + evaluationCutoffAt
                + ", recipientReferencePresent="
                + (recipientAccountRef != null)
                + ", deviceReferencePresent="
                + (deviceRef != null)
                + ", sensitiveFields=REDACTED]";
    }

    private static void validate(
            UUID transactionId,
            TransactionType transactionType,
            Instant evaluationCutoffAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            String deviceRef,
            String traceId
    ) {
        if (!ExternalRiskContracts.isUuidV4(transactionId)
                || transactionType == null
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
}
