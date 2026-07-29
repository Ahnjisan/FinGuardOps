package com.aifds.backend.behavior.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;

@JsonDeserialize(using = BehaviorEventCreateRequestDeserializer.class)
public record BehaviorEventCreateRequest(
        @NotNull String eventId,
        @NotNull String eventType,
        @NotNull String occurredAt,
        @NotNull String externalCustomerRef,
        String accountRef,
        String deviceRef,
        String transactionId,
        String beneficiaryRef
) {
}
