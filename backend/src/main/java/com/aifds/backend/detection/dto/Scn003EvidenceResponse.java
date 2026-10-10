package com.aifds.backend.detection.dto;

import java.time.Instant;

/** Allowlisted adopted evidence; account references and provider body stay private. */
public record Scn003EvidenceResponse(
        String sourceVersion,
        String providerCode,
        Instant providerAsOf,
        Instant lookedUpAt,
        boolean recipientAccountMatched,
        boolean priorApprovedRecipientTransferObserved
) {
}
