package com.aifds.backend.behavior.service;

import com.aifds.backend.behavior.entity.BehaviorEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BehaviorEventIntakeSnapshot(
        UUID eventId,
        BehaviorEventType eventType,
        UUID transactionId,
        Instant occurredAt,
        Instant createdAt,
        String requestFingerprint
) {

    public BehaviorEventIntakeSnapshot {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(
                requestFingerprint,
                "requestFingerprint must not be null"
        );
    }
}
