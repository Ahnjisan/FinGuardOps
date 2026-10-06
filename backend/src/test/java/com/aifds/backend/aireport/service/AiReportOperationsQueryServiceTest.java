package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.dto.AiReportUsageQuery;
import com.aifds.backend.aireport.repository.AiReportOperationsQueryRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiReportOperationsQueryServiceTest {
    @Test
    void unknownAttemptTokensRemainUnknownAndCostIsNotZero() {
        var repo = mock(AiReportOperationsQueryRepository.class);
        var query = AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                "to", new String[]{"2026-10-02T00:00:00Z"}), true);
        when(repo.counts(query)).thenReturn(new AiReportOperationsQueryRepository.Counts(
                2, 1, 2, 2, 0, 0, 0, 0, 1, 100L, 0, 10L));
        var summary = new AiReportOperationsQueryService(repo).summary(query, "trace-query-001");
        assertEquals(2, summary.requestCount());
        assertEquals(1, summary.executionCount());
        assertEquals(2, summary.providerCallCount());
        assertNull(summary.inputTokens());
        assertEquals(10L, summary.outputTokens());
        assertNull(summary.totalTokens());
        assertNull(summary.estimatedCost());
        assertNull(summary.costBreakdown());
    }

    @Test
    void missingOutputIsUnknownButNoAttemptsHaveZeroTokensAndNoCostRow() {
        var repo = mock(AiReportOperationsQueryRepository.class);
        var query = AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                "to", new String[]{"2026-10-02T00:00:00Z"}), true);
        when(repo.counts(query)).thenReturn(new AiReportOperationsQueryRepository.Counts(
                1, 1, 1, 1, 0, 0, 0, 0, 0, 12L, 1, 8L));
        var service = new AiReportOperationsQueryService(repo);
        var unknown = service.summary(query, "trace-query-001");
        assertEquals(12L, unknown.inputTokens());
        assertNull(unknown.outputTokens());
        assertNull(unknown.totalTokens());
        assertNull(unknown.costBreakdown());

        when(repo.counts(query)).thenReturn(new AiReportOperationsQueryRepository.Counts(
                1, 0, 0, 1, 0, 0, 0, 1, 0, null, 0, null));
        var withoutCalls = service.summary(query, "trace-query-001");
        assertEquals(0L, withoutCalls.inputTokens());
        assertEquals(0L, withoutCalls.outputTokens());
        assertEquals(List.of(), withoutCalls.costBreakdown());
        assertNull(withoutCalls.estimatedCost());
    }
}
