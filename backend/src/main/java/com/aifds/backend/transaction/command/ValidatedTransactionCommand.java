package com.aifds.backend.transaction.command;

import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ValidatedTransactionCommand(
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

    public ValidatedTransactionCommand {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(transactionType, "transactionType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(
                externalCustomerRef,
                "externalCustomerRef must not be null"
        );
        Objects.requireNonNull(senderAccountRef, "senderAccountRef must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
    }

    public TransactionFingerprintInput toFingerprintInput() {
        return new TransactionFingerprintInput(
                transactionId,
                transactionType,
                amount,
                currencyCode,
                occurredAt,
                externalCustomerRef,
                senderAccountRef,
                recipientAccountRef,
                channel,
                deviceRef
        );
    }
}
