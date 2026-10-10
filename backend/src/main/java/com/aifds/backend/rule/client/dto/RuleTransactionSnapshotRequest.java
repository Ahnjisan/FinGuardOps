package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RuleTransactionSnapshotRequest(
        @JsonProperty(required = true) UUID transactionId,
        @JsonProperty(required = true) RuleTransactionType transactionType,
        @JsonProperty(required = true) String amount,
        @JsonProperty(required = true) String currencyCode,
        @JsonProperty(required = true) Instant occurredAt,
        @JsonProperty(required = true) String externalCustomerRef,
        @JsonProperty(required = true) String senderAccountRef,
        @JsonProperty(required = true) String recipientAccountRef,
        @JsonProperty(required = true) String deviceRef
) {

    public RuleTransactionSnapshotRequest {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        RuleAnalysisDtoContracts.requireUuidV4(transactionId, "transactionId");
        Objects.requireNonNull(transactionType, "transactionType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        RuleAnalysisDtoContracts.requireAmount(amount, "amount");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        RuleAnalysisDtoContracts.requireCurrencyCode(currencyCode);
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        RuleAnalysisDtoContracts.requireMicrosecondInstant(occurredAt, "occurredAt");
        Objects.requireNonNull(
                externalCustomerRef,
                "externalCustomerRef must not be null"
        );
        Objects.requireNonNull(senderAccountRef, "senderAccountRef must not be null");
        boolean transfer = transactionType == RuleTransactionType.ACCOUNT_TRANSFER
                || transactionType == RuleTransactionType.OPEN_BANKING_TRANSFER;
        if (transfer) {
            Objects.requireNonNull(recipientAccountRef,
                    "recipientAccountRef must not be null for a transfer");
        } else if (recipientAccountRef != null) {
            throw new IllegalArgumentException(
                    "recipientAccountRef must be null for a non-transfer");
        }
        RuleAnalysisDtoContracts.requireReference(
                externalCustomerRef,
                "externalCustomerRef"
        );
        RuleAnalysisDtoContracts.requireReference(senderAccountRef, "senderAccountRef");
        if (transfer) {
            RuleAnalysisDtoContracts.requireReference(
                    recipientAccountRef, "recipientAccountRef");
        }
        RuleAnalysisDtoContracts.requireOptionalReference(deviceRef, "deviceRef");
    }
}
