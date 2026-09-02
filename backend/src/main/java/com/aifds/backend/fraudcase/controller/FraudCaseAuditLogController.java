package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListResponse;
import com.aifds.backend.fraudcase.query.FraudCaseAuditLogQuery;
import com.aifds.backend.fraudcase.service.FraudCaseAuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/cases")
public class FraudCaseAuditLogController {

    private final FraudCaseAuditLogService service;

    public FraudCaseAuditLogController(FraudCaseAuditLogService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/audit-logs")
    public ResponseEntity<FraudCaseAuditLogListResponse> list(
            @PathVariable String caseId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId,
            HttpServletRequest servletRequest
    ) {
        Map<String, List<String>> queryParameters = servletRequest
                .getParameterMap()
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(Arrays.asList(
                                entry.getValue().clone()
                        ))
                ));
        return ResponseEntity.ok(service.findAll(
                new FraudCaseAuditLogQuery.Request(
                        caseId,
                        queryParameters
                ),
                traceId
        ));
    }
}
