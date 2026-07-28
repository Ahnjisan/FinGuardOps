package com.aifds.backend.transaction.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;

@JsonDeserialize(using = TransactionCreateRequestDeserializer.class)
public record TransactionCreateRequest(
        @NotNull String transactionId,
        @NotNull String transactionType,
        @NotNull String amount,
        @NotNull String currencyCode,
        @NotNull String occurredAt,
        @NotNull String externalCustomerRef,
        @NotNull String senderAccountRef,
        String recipientAccountRef,
        @NotNull String channel,
        String deviceRef
) {
}
