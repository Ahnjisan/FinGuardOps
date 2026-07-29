package com.aifds.backend.behavior.fingerprint;

import com.aifds.backend.behavior.entity.BehaviorEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BehaviorEventFingerprintInput(
        UUID eventId,
        BehaviorEventType eventType,
        Instant occurredAt,
        String externalCustomerRef,
        String accountRef,
        String deviceRef,
        UUID transactionId,
        String beneficiaryRef
) {

    public BehaviorEventFingerprintInput {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(
                externalCustomerRef,
                "externalCustomerRef must not be null"
        );
    }
}
