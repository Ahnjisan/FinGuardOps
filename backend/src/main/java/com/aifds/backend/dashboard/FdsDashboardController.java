package com.aifds.backend.dashboard;

import com.aifds.backend.common.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fds-dashboard")
public class FdsDashboardController {
    private final FdsDashboardService service;

    public FdsDashboardController(FdsDashboardService service) {
        this.service = service;
    }

    @GetMapping("/transactions")
    public FdsDashboardResponse.Transactions transactions(HttpServletRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        Instant asOf = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return service.transactions(FdsDashboardRange.parse(request.getParameterMap(), asOf), asOf, traceId);
    }

    @GetMapping("/cases")
    public FdsDashboardResponse.Cases cases(HttpServletRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        Instant asOf = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return service.cases(FdsDashboardRange.parse(request.getParameterMap(), asOf), asOf, traceId);
    }
}
