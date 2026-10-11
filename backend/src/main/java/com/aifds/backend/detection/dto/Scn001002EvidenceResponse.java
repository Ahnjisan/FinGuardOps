package com.aifds.backend.detection.dto;

import java.time.Instant;

/** Limited, identifier-free projection of the adopted scenario snapshot. */
public record Scn001002EvidenceResponse(
        String sourceVersion,
        String baselineStatus,
        long baselineCount,
        long baselineDays,
        String recipientStatus,
        String deviceStatus,
        String providerCode,
        Instant providerAsOf,
        Instant lookedUpAt,
        boolean recipientAccountMatched,
        boolean exactDeviceMatched
) { }
