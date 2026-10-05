package com.aifds.backend.aireport.entity;

import java.time.Instant;
import java.util.UUID;

public record AiReportRequest(long id, UUID aiRequestId, long casePk, Long executionPk,
                              Long reportPk, String idempotencyKey, String fingerprint,
                              int detectionResultVersion, String promptVersion, String modelVersion,
                              AiReportStatus status, boolean cacheHit, boolean executionShared,
                              String traceId, Instant requestedAt) { }
