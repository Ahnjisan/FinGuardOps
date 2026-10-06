package com.aifds.backend.aireport.controller;

import com.aifds.backend.aireport.dto.AiReportOperationsDtos;
import com.aifds.backend.aireport.dto.AiReportUsageQuery;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.service.AiReportOperationsQueryService;
import com.aifds.backend.common.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class AiReportOperationsController {
    private final AiReportOperationsQueryService service;

    public AiReportOperationsController(AiReportOperationsQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/ai-report-requests/{aiRequestId}")
    public AiReportOperationsDtos.Detail detail(@PathVariable String aiRequestId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        try {
            UUID id = UUID.fromString(aiRequestId);
            if (!id.toString().equals(aiRequestId) || id.version() != 4 || id.variant() != 2) {
                throw new IllegalArgumentException();
            }
            return service.detail(id, traceId);
        } catch (IllegalArgumentException exception) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }

    @GetMapping("/api/v1/ai-report-usage")
    public AiReportOperationsDtos.ListResult list(HttpServletRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        return service.list(AiReportUsageQuery.parse(request.getParameterMap(), false), traceId);
    }

    @GetMapping("/api/v1/ai-report-usage/summary")
    public AiReportOperationsDtos.Summary summary(HttpServletRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        return service.summary(AiReportUsageQuery.parse(request.getParameterMap(), true), traceId);
    }
}
