package com.aifds.backend.scenario.mock;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fds-mock-fund-links")
public class MockFundLinkIntakeController {
    private final MockFundLinkIntakeService service;

    public MockFundLinkIntakeController(MockFundLinkIntakeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Response> ingest(@RequestBody MockFundLinkRequest request) {
        boolean created = service.ingest(request);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new Response(request.linkId(), "LOCAL_FDS_MOCK", "v2"));
    }

    public record Response(UUID linkId, String sourceCode, String sourceVersion) { }
}
