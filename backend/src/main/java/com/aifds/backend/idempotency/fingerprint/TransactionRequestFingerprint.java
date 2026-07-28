package com.aifds.backend.idempotency.fingerprint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Component
public class TransactionRequestFingerprint {

    private final ObjectMapper objectMapper;

    public TransactionRequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalize(TransactionFingerprintInput input) {
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("transactionId", input.transactionId().toString());
        normalized.put("transactionType", input.transactionType().name());
        normalized.put("amount", input.amount().toBigIntegerExact().toString());
        normalized.put("currencyCode", input.currencyCode());
        normalized.put(
                "occurredAt",
                DateTimeFormatter.ISO_INSTANT.format(input.occurredAt())
        );
        normalized.put("externalCustomerRef", input.externalCustomerRef());
        normalized.put("senderAccountRef", input.senderAccountRef());
        if (input.recipientAccountRef() == null) {
            normalized.putNull("recipientAccountRef");
        } else {
            normalized.put("recipientAccountRef", input.recipientAccountRef());
        }
        normalized.put("channel", input.channel().name());
        if (input.deviceRef() == null) {
            normalized.putNull("deviceRef");
        } else {
            normalized.put("deviceRef", input.deviceRef());
        }

        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize normalized transaction fingerprint input",
                    exception
            );
        }
    }

    public String calculate(TransactionFingerprintInput input) {
        byte[] normalizedBytes = normalize(input).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
