package com.aifds.backend.transaction.dto;

import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionDetailItemResponse(
        UUID transactionId,
        TransactionType transactionType,
        String amount,
        String currencyCode,
        Instant occurredAt,
        String externalCustomerRef,
        String senderAccountRef,
        String recipientAccountRef,
        TransactionChannel channel,
        String deviceRef,
        TransactionProcessingStatus processingStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
