package com.aifds.backend.behavior.dto;

import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record BehaviorEventCreateResponse(
        UUID eventId,
        BehaviorEventType eventType,
        UUID transactionId,
        Instant occurredAt,
        Instant createdAt,
        String traceId
) {

    public BehaviorEventCreateResponse {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }
}
