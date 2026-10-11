package com.aifds.backend.scenario;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Identifier-free, cutoff-fixed facts passed to the versioned Rule/ML engines. */
public record ScenarioFacts(
        String sourceVersion,
        String baselineStatus,
        long baselineCount,
        long baselineDays,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal baselineTotal,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal baselineMean,
        String recipientStatus,
        UUID beneficiaryEventId,
        Instant beneficiaryRegisteredAt,
        String deviceStatus,
        UUID deviceEventId,
        Instant deviceFirstSeenAt,
        String limitStatus,
        String limitScope,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal previousLimit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal newLimit,
        String priorLimitExceeded,
        UUID passwordEventId,
        Instant passwordChangedAt,
        UUID limitEventId,
        Instant limitChangedAt,
        String identityStatus,
        String appSecurityStatus,
        Instant appSecurityAt,
        String networkRegionStatus,
        Instant networkRegionAt,
        boolean exactProviderDeviceMatch,
        UUID transferCoverageFactId,
        UUID deviceCoverageFactId,
        UUID deviceTrustFactId,
        UUID limitFactId,
        UUID identityFactId,
        UUID appSecurityFactId,
        UUID networkRegionFactId
) {
    public ScenarioFacts {
        if (!"SCN001-002-contract-v1".equals(sourceVersion)) {
            throw new IllegalArgumentException("Unsupported scenario facts version");
        }
    }
}
