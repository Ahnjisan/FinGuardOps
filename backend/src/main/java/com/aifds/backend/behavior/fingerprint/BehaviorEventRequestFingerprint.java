package com.aifds.backend.behavior.fingerprint;

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
public class BehaviorEventRequestFingerprint {

    private final ObjectMapper objectMapper;

    public BehaviorEventRequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalize(BehaviorEventFingerprintInput input) {
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("eventId", input.eventId().toString());
        normalized.put("eventType", input.eventType().name());
        normalized.put(
                "occurredAt",
                DateTimeFormatter.ISO_INSTANT.format(input.occurredAt())
        );
        normalized.put("externalCustomerRef", input.externalCustomerRef());
        putNullable(normalized, "accountRef", input.accountRef());
        putNullable(normalized, "deviceRef", input.deviceRef());
        if (input.transactionId() == null) {
            normalized.putNull("transactionId");
        } else {
            normalized.put("transactionId", input.transactionId().toString());
        }
        putNullable(normalized, "beneficiaryRef", input.beneficiaryRef());

        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize normalized behavior event fingerprint input",
                    exception
            );
        }
    }

    public String calculate(BehaviorEventFingerprintInput input) {
        byte[] normalizedBytes = normalize(input).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    private void putNullable(
            ObjectNode target,
            String field,
            String value
    ) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }
}
