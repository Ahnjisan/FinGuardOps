package com.aifds.backend.transaction.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.transaction.dto.TransactionDetailResponse;
import com.aifds.backend.transaction.dto.TransactionListRequest;
import com.aifds.backend.transaction.dto.TransactionListResponse;
import com.aifds.backend.transaction.service.TransactionQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionQueryController {

    private final TransactionQueryService transactionQueryService;

    public TransactionQueryController(
            TransactionQueryService transactionQueryService
    ) {
        this.transactionQueryService = transactionQueryService;
    }

    @GetMapping
    public ResponseEntity<TransactionListResponse> list(
            @RequestParam(required = false) String occurredAtFrom,
            @RequestParam(required = false) String occurredAtTo,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String processingStatus,
            @RequestParam(required = false) String externalCustomerRef,
            @RequestParam(required = false) String accountRef,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String sort,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId,
            HttpServletRequest servletRequest
    ) {
        TransactionListRequest request = new TransactionListRequest(
                occurredAtFrom,
                occurredAtTo,
                transactionType,
                processingStatus,
                externalCustomerRef,
                accountRef,
                page,
                size,
                sort,
                valueCount(servletRequest, "transactionType"),
                valueCount(servletRequest, "processingStatus"),
                valueCount(servletRequest, "sort")
        );
        return ResponseEntity.ok(
                transactionQueryService.findAll(request, traceId)
        );
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> detail(
            @PathVariable String transactionId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId
    ) {
        return ResponseEntity.ok(
                transactionQueryService.findByTransactionId(
                        transactionId,
                        traceId
                )
        );
    }

    private int valueCount(
            HttpServletRequest request,
            String parameterName
    ) {
        String[] values = request.getParameterValues(parameterName);
        return values == null ? 0 : values.length;
    }
}
