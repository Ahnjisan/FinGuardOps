package com.aifds.backend.scenario.auth;

import com.aifds.backend.common.trace.TraceIdFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions/{transactionId}/mock-auth")
public class MockAuthController {
    private final MockAuthService service;

    public MockAuthController(MockAuthService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<MockAuthResponse> decide(@PathVariable UUID transactionId,
            @RequestBody MockAuthRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        return ResponseEntity.ok(service.decide(transactionId, request, traceId));
    }
}
