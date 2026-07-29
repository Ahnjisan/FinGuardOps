package com.aifds.backend.behavior.command;

import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.fingerprint.BehaviorEventFingerprintInput;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ValidatedBehaviorEventCommand(
        UUID eventId,
        BehaviorEventType eventType,
        Instant occurredAt,
        String externalCustomerRef,
        String accountRef,
        String deviceRef,
        UUID transactionId,
        String beneficiaryRef
) {

    public ValidatedBehaviorEventCommand {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(
                externalCustomerRef,
                "externalCustomerRef must not be null"
        );
    }

    public BehaviorEventFingerprintInput toFingerprintInput() {
        return new BehaviorEventFingerprintInput(
                eventId,
                eventType,
                occurredAt,
                externalCustomerRef,
                accountRef,
                deviceRef,
                transactionId,
                beneficiaryRef
        );
    }
}
