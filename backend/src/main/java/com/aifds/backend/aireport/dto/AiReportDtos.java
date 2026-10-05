package com.aifds.backend.aireport.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiReportDtos {
    private AiReportDtos() { }

    public record CreateRequest(int detectionResultVersion, String regenerationReason) { }
    public record KeyReason(String reasonCode, String description) { }
    public record RuleEvidence(String ruleCode, String ruleVersion, String reasonCode, int scoreContribution) { }
    public record GenerationRequest(UUID caseId, int detectionResultVersion, String riskLevel,
                                    int riskScore, String ruleSetVersion, List<RuleEvidence> ruleEvidence,
                                    String traceId) { }
    public record Attempt(String provider, String modelDigest, String quantization,
                          Integer inputTokens, Integer outputTokens, int latencyMs, String outcome) { }
    public record Content(String summary, List<KeyReason> keyReasons,
                          List<String> investigationChecklist) { }
    public record GenerationResult(String status, String source, Content content, String failureCode,
                                   String modelVersion, String promptVersion, List<Attempt> attempts) { }
    public record ModelIdentity(String modelVersion, String promptVersion) { }
    public record RequestStatus(UUID aiRequestId, UUID executionId, boolean executionShared,
                                UUID initiatingAiRequestId, UUID reportId, UUID caseId,
                                int detectionResultVersion, String reportStatus,
                                String reportSource, UUID sourceAiRequestId, boolean cacheHit,
                                Instant requestedAt, Instant generatedAt, String failureCode,
                                String resultLocation, String traceId) { }
    public record Report(UUID reportId, UUID executionId, UUID initiatingAiRequestId, UUID caseId,
                         int detectionResultVersion, String reportStatus, String reportSource,
                         String summary, List<KeyReason> keyReasons, String timelineSummary,
                         List<String> investigationChecklist, String promptVersion,
                         String modelVersion, Instant generatedAt, String failureCode, String traceId) { }
    public record Current(UUID caseId, Report currentReport, RequestStatus latestRequest, String traceId) { }
}
