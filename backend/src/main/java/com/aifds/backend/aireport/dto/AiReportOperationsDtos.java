package com.aifds.backend.aireport.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiReportOperationsDtos {
    private AiReportOperationsDtos() { }

    public record Attempt(int attemptNumber, String provider, String model, String outcome,
                          Integer inputTokens, Integer outputTokens, Long totalTokens,
                          String estimatedCost, String costCurrency, int latencyMs) { }

    public record Item(UUID aiRequestId, UUID executionId, boolean executionShared,
                       UUID initiatingAiRequestId, UUID reportId, UUID caseId,
                       int detectionResultVersion, String reportStatus, String reportSource,
                       UUID sourceAiRequestId, String lastProvider, String lastModel,
                       String promptVersion, String modelVersion, Long inputTokens,
                       Long outputTokens, Long totalTokens, String estimatedCost,
                       String costCurrency, List<Object> costBreakdown, Long latencyMs,
                       boolean cacheHit, boolean fallbackUsed, Instant requestedAt,
                       Instant completedAt, String failureCode, String traceId) { }

    public record Detail(UUID aiRequestId, UUID executionId, boolean executionShared,
                         UUID initiatingAiRequestId, UUID reportId, UUID caseId,
                         int detectionResultVersion, String reportStatus, String reportSource,
                         UUID sourceAiRequestId, String lastProvider, String lastModel,
                         String promptVersion, String modelVersion, Long inputTokens,
                         Long outputTokens, Long totalTokens, String estimatedCost,
                         String costCurrency, List<Object> costBreakdown, Long latencyMs,
                         boolean cacheHit, boolean fallbackUsed, Instant requestedAt,
                         Instant completedAt, String failureCode, String traceId,
                         boolean usageFinalized, String requestedByRef, List<Attempt> attempts,
                         String queryTraceId) {
        public Detail(Item item, boolean usageFinalized, String requestedByRef,
                      List<Attempt> attempts, String queryTraceId) {
            this(item.aiRequestId(), item.executionId(), item.executionShared(),
                    item.initiatingAiRequestId(), item.reportId(), item.caseId(),
                    item.detectionResultVersion(), item.reportStatus(), item.reportSource(),
                    item.sourceAiRequestId(), item.lastProvider(), item.lastModel(),
                    item.promptVersion(), item.modelVersion(), item.inputTokens(),
                    item.outputTokens(), item.totalTokens(), item.estimatedCost(),
                    item.costCurrency(), item.costBreakdown(), item.latencyMs(),
                    item.cacheHit(), item.fallbackUsed(), item.requestedAt(),
                    item.completedAt(), item.failureCode(), item.traceId(), usageFinalized,
                    requestedByRef, attempts, queryTraceId);
        }
    }

    public record Page(int number, int size, long totalElements, long totalPages,
                       boolean first, boolean last) { }

    public record ListResult(List<Item> content, Page page, String traceId) { }

    public record Summary(Instant from, Instant to, long requestCount, long executionCount,
                          long providerCallCount, long successCount, long failureCount,
                          long inProgressCount, long fallbackCount, long cacheHitCount,
                          Long inputTokens, Long outputTokens, Long totalTokens,
                          String estimatedCost, String costCurrency, List<Object> costBreakdown,
                          Long averageLatencyMs, String traceId) { }
}
