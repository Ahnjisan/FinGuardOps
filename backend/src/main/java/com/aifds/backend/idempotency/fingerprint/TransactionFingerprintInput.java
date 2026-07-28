package com.aifds.backend.idempotency.fingerprint;

import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransactionFingerprintInput(
        UUID transactionId,
        TransactionType transactionType,
        BigDecimal amount,
        String currencyCode,
        Instant occurredAt,
        String externalCustomerRef,
        String senderAccountRef,
        String recipientAccountRef,
        TransactionChannel channel,
        String deviceRef
) {

    public TransactionFingerprintInput {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(transactionType, "transactionType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(externalCustomerRef, "externalCustomerRef must not be null");
        Objects.requireNonNull(senderAccountRef, "senderAccountRef must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
    }
}
