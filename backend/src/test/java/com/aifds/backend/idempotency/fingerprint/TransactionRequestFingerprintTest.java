package com.aifds.backend.idempotency.fingerprint;

import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRequestFingerprintTest {

    private static final String EXPECTED_NORMALIZED_JSON =
            "{\"transactionId\":\"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001\","
                    + "\"transactionType\":\"ACCOUNT_TRANSFER\","
                    + "\"amount\":\"1250000\","
                    + "\"currencyCode\":\"KRW\","
                    + "\"occurredAt\":\"2026-07-23T01:15:30Z\","
                    + "\"externalCustomerRef\":\"cust_ref_demo_a7f2\","
                    + "\"senderAccountRef\":\"acct_ref_demo_s91c\","
                    + "\"recipientAccountRef\":\"acct_ref_demo_r44d\","
                    + "\"channel\":\"MOBILE_BANKING\","
                    + "\"deviceRef\":\"device_ref_demo_18b3\"}";
    private static final String EXPECTED_SHA_256 =
            "48a83a0d844fdebefc83065464bdfce8d22a15de4bd55400000b56eeb49f2c6d";

    private final TransactionRequestFingerprint fingerprint =
            new TransactionRequestFingerprint(new ObjectMapper());

    @Test
    void normalizesFieldsInFixedOrderWithoutWhitespace() {
        assertThat(fingerprint.normalize(baseInput())).isEqualTo(EXPECTED_NORMALIZED_JSON);
    }

    @Test
    void calculatesKnownSha256DigestWithoutUsingImplementationForExpectedValue() {
        assertThat(fingerprint.calculate(baseInput())).isEqualTo(EXPECTED_SHA_256);
    }

    @Test
    void producesSameFingerprintForEquivalentInputs() {
        assertThat(fingerprint.calculate(baseInput()))
                .isEqualTo(fingerprint.calculate(baseInput()));
    }

    @Test
    void producesDifferentFingerprintWhenAnyIncludedFieldChanges() {
        TransactionFingerprintInput base = baseInput();
        String baseFingerprint = fingerprint.calculate(base);
        List<TransactionFingerprintInput> variants = List.of(
                copy(base, UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430002"),
                        base.transactionType(), base.amount(), base.currencyCode(), base.occurredAt(),
                        base.externalCustomerRef(), base.senderAccountRef(),
                        base.recipientAccountRef(), base.channel(), base.deviceRef()),
                copy(base, base.transactionId(), TransactionType.OPEN_BANKING_TRANSFER,
                        base.amount(), base.currencyCode(), base.occurredAt(),
                        base.externalCustomerRef(), base.senderAccountRef(),
                        base.recipientAccountRef(), base.channel(), base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), new BigDecimal("1250001"),
                        base.currencyCode(), base.occurredAt(), base.externalCustomerRef(),
                        base.senderAccountRef(), base.recipientAccountRef(), base.channel(),
                        base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(), "USD",
                        base.occurredAt(), base.externalCustomerRef(), base.senderAccountRef(),
                        base.recipientAccountRef(), base.channel(), base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(),
                        base.currencyCode(), base.occurredAt().plusSeconds(1),
                        base.externalCustomerRef(), base.senderAccountRef(),
                        base.recipientAccountRef(), base.channel(), base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(),
                        base.currencyCode(), base.occurredAt(), "cust_ref_demo_changed",
                        base.senderAccountRef(), base.recipientAccountRef(), base.channel(),
                        base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(),
                        base.currencyCode(), base.occurredAt(), base.externalCustomerRef(),
                        "acct_ref_sender_changed", base.recipientAccountRef(), base.channel(),
                        base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(),
                        base.currencyCode(), base.occurredAt(), base.externalCustomerRef(),
                        base.senderAccountRef(), "acct_ref_recipient_changed", base.channel(),
                        base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(),
                        base.currencyCode(), base.occurredAt(), base.externalCustomerRef(),
                        base.senderAccountRef(), base.recipientAccountRef(),
                        TransactionChannel.OPEN_BANKING, base.deviceRef()),
                copy(base, base.transactionId(), base.transactionType(), base.amount(),
                        base.currencyCode(), base.occurredAt(), base.externalCustomerRef(),
                        base.senderAccountRef(), base.recipientAccountRef(), base.channel(),
                        "device_ref_changed")
        );

        assertThat(variants)
                .extracting(fingerprint::calculate)
                .allSatisfy(value -> assertThat(value).isNotEqualTo(baseFingerprint));
    }

    @Test
    void normalizesMissingOptionalFieldsAsJsonNull() {
        TransactionFingerprintInput input = copy(
                baseInput(),
                baseInput().transactionId(),
                baseInput().transactionType(),
                baseInput().amount(),
                baseInput().currencyCode(),
                baseInput().occurredAt(),
                baseInput().externalCustomerRef(),
                baseInput().senderAccountRef(),
                null,
                baseInput().channel(),
                null
        );

        assertThat(fingerprint.normalize(input))
                .contains("\"recipientAccountRef\":null")
                .contains("\"deviceRef\":null");
    }

    private TransactionFingerprintInput baseInput() {
        return new TransactionFingerprintInput(
                UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-23T01:15:30Z"),
                "cust_ref_demo_a7f2",
                "acct_ref_demo_s91c",
                "acct_ref_demo_r44d",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_demo_18b3"
        );
    }

    private TransactionFingerprintInput copy(
            TransactionFingerprintInput ignored,
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
