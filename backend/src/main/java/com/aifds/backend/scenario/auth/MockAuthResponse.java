package com.aifds.backend.scenario.auth;

import java.time.Instant;
import java.util.UUID;

public record MockAuthResponse(UUID transactionId, UUID decisionId,
        String appliedResult, String processingStatus, Instant decidedAt,
        String sourceCode, String sourceVersion) { }
