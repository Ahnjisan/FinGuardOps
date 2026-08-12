package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RuleBehaviorEventSnapshotRequest(
        @JsonProperty(required = true) UUID eventId,
        @JsonProperty(required = true) RuleBehaviorEventType eventType,
        @JsonProperty(required = true) Instant occurredAt,
        @JsonProperty(required = true) String externalCustomerRef,
        @JsonProperty(required = true) String accountRef,
        @JsonProperty(required = true) String deviceRef,
        @JsonProperty(required = true) String beneficiaryRef
) {

    public RuleBehaviorEventSnapshotRequest {
        Objects.requireNonNull(eventId, "eventId must not be null");
        RuleAnalysisDtoContracts.requireUuidV4(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        RuleAnalysisDtoContracts.requireMicrosecondInstant(occurredAt, "occurredAt");
        Objects.requireNonNull(
                externalCustomerRef,
                "externalCustomerRef must not be null"
        );
        RuleAnalysisDtoContracts.requireReference(
                externalCustomerRef,
                "externalCustomerRef"
        );
        RuleAnalysisDtoContracts.requireOptionalReference(accountRef, "accountRef");
        RuleAnalysisDtoContracts.requireOptionalReference(deviceRef, "deviceRef");
        RuleAnalysisDtoContracts.requireOptionalReference(
                beneficiaryRef,
                "beneficiaryRef"
        );
        validateReferenceCombination(eventType, accountRef, deviceRef, beneficiaryRef);
    }

    private static void validateReferenceCombination(
            RuleBehaviorEventType eventType,
            String accountRef,
            String deviceRef,
            String beneficiaryRef
    ) {
        boolean valid = switch (eventType) {
            case DEVICE_REGISTERED -> deviceRef != null && beneficiaryRef == null;
            case PASSWORD_CHANGED -> beneficiaryRef == null;
            case TRANSFER_LIMIT_CHANGED -> accountRef != null && beneficiaryRef == null;
            case BENEFICIARY_REGISTERED ->
                    accountRef != null && beneficiaryRef != null;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "behavior event references do not match eventType"
            );
        }
    }
}
