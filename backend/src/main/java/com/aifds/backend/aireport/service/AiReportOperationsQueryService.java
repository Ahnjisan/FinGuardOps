package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.dto.AiReportOperationsDtos;
import com.aifds.backend.aireport.dto.AiReportUsageQuery;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.repository.AiReportOperationsQueryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AiReportOperationsQueryService {
    private final AiReportOperationsQueryRepository repository;

    public AiReportOperationsQueryService(AiReportOperationsQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AiReportOperationsDtos.Detail detail(UUID id, String traceId) {
        var row = repository.detail(id).orElseThrow(() ->
                new AiReportException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
        var attempts = attempts(row);
        return new AiReportOperationsDtos.Detail(item(row, attempts), row.fallbackTriggerCode(),
                row.executionPk() == null || terminal(row.executionStatus()),
                row.requestedBy(), attempts, traceId);
    }

    @Transactional(readOnly = true)
    public AiReportOperationsDtos.ListResult list(AiReportUsageQuery query, String traceId) {
        long total = repository.count(query);
        List<AiReportOperationsDtos.Item> items = new ArrayList<>();
        for (var row : repository.list(query)) items.add(item(row, attempts(row)));
        long totalPages = total == 0 ? 0 : 1 + (total - 1) / query.size();
        return new AiReportOperationsDtos.ListResult(items,
                new AiReportOperationsDtos.Page(query.page(), query.size(), total, totalPages,
                        query.page() == 0, query.page() >= totalPages - 1), traceId);
    }

    @Transactional(readOnly = true)
    public AiReportOperationsDtos.Summary summary(AiReportUsageQuery query, String traceId) {
        var c = repository.counts(query);
        Long input = aggregateTokens(c.attempts(), c.missingInput(), c.input());
        Long output = aggregateTokens(c.attempts(), c.missingOutput(), c.output());
        Long total = input == null || output == null ? null : input + output;
        return new AiReportOperationsDtos.Summary(query.from(), query.to(), c.requests(),
                c.executions(), c.attempts(), c.successes(), c.failures(), c.inProgress(),
                c.fallbacks(), c.cacheHits(), input, output, total, null, null,
                c.attempts() == 0 ? List.of() : null, null, traceId);
    }

    private List<AiReportOperationsDtos.Attempt> attempts(AiReportOperationsQueryRepository.Row row) {
        return row.executionPk() == null ? List.of() : repository.attempts(row.executionPk());
    }

    private Long aggregateTokens(long attempts, long missing, Long sum) {
        if (attempts == 0) return 0L;
        if (missing > 0) return null;
        return sum;
    }

    private AiReportOperationsDtos.Item item(AiReportOperationsQueryRepository.Row row,
                                            List<AiReportOperationsDtos.Attempt> attempts) {
        Long input = tokens(attempts, true);
        Long output = tokens(attempts, false);
        UUID initiating = row.executionPk() == null ? null : repository.initiatingRequest(row.executionPk());
        UUID source = row.cacheHit() && row.reportPk() != null
                ? repository.initiatingRequest(repository.reportExecution(row.reportPk())) : null;
        var last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        return new AiReportOperationsDtos.Item(row.aiRequestId(), row.executionId(),
                row.executionShared(), initiating, row.reportId(), row.caseId(),
                row.detectionResultVersion(), row.status(), row.reportSource(), source,
                last == null ? null : last.provider(), last == null ? null : last.model(),
                row.promptVersion(), row.modelVersion(), input, output,
                input == null || output == null ? null : input + output,
                null, null, attempts.isEmpty() ? List.of() : null, null,
                row.cacheHit(), row.executionPk() != null &&
                "FALLBACK_COMPLETED".equals(row.executionStatus()), row.requestedAt(),
                null, row.failureCode(), row.traceId());
    }

    private Long tokens(List<AiReportOperationsDtos.Attempt> attempts, boolean input) {
        long total = 0;
        for (var attempt : attempts) {
            Integer count = input ? attempt.inputTokens() : attempt.outputTokens();
            if (count == null) return null;
            total += count;
        }
        return total;
    }

    private boolean terminal(String status) {
        return "COMPLETED".equals(status) || "FALLBACK_COMPLETED".equals(status)
                || "FAILED".equals(status);
    }
}
