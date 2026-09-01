package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.dto.FraudCaseDetailResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseListRequest;
import com.aifds.backend.fraudcase.dto.FraudCaseListResponse;
import com.aifds.backend.fraudcase.service.FraudCaseQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
public class FraudCaseQueryController {

    private final FraudCaseQueryService fraudCaseQueryService;

    public FraudCaseQueryController(
            FraudCaseQueryService fraudCaseQueryService
    ) {
        this.fraudCaseQueryService = fraudCaseQueryService;
    }

    @GetMapping
    public ResponseEntity<FraudCaseListResponse> list(
            @RequestParam(required = false) String caseStatus,
            @RequestParam(required = false) String finalDisposition,
            @RequestParam(required = false) String assigneeRef,
            @RequestParam(required = false) String createdAtFrom,
            @RequestParam(required = false) String createdAtTo,
            @RequestParam(required = false) String lastChangedAtFrom,
            @RequestParam(required = false) String lastChangedAtTo,
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String sort,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId,
            HttpServletRequest servletRequest
    ) {
        FraudCaseListRequest request = new FraudCaseListRequest(
                caseStatus,
                finalDisposition,
                assigneeRef,
                createdAtFrom,
                createdAtTo,
                lastChangedAtFrom,
                lastChangedAtTo,
                transactionId,
                page,
                size,
                sort,
                valueCount(servletRequest, "caseStatus"),
                valueCount(servletRequest, "finalDisposition"),
                valueCount(servletRequest, "assigneeRef"),
                valueCount(servletRequest, "createdAtFrom"),
                valueCount(servletRequest, "createdAtTo"),
                valueCount(servletRequest, "lastChangedAtFrom"),
                valueCount(servletRequest, "lastChangedAtTo"),
                valueCount(servletRequest, "transactionId"),
                valueCount(servletRequest, "page"),
                valueCount(servletRequest, "size"),
                valueCount(servletRequest, "sort")
        );
        return ResponseEntity.ok(fraudCaseQueryService.findAll(
                request,
                traceId
        ));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<FraudCaseDetailResponse> detail(
            @PathVariable String caseId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId
    ) {
        return ResponseEntity.ok(fraudCaseQueryService.findByCaseId(
                caseId,
                traceId
        ));
    }

    private int valueCount(
            HttpServletRequest request,
            String parameterName
    ) {
        String[] values = request.getParameterValues(parameterName);
        return values == null ? 0 : values.length;
    }
}
