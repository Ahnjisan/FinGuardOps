package com.aifds.backend.scenario.mock;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fds-mock-open-accounts")
public class MockOpenAccountIntakeController {
    private final MockOpenAccountIntakeService service;

    public MockOpenAccountIntakeController(MockOpenAccountIntakeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Response> ingest(@RequestBody MockOpenAccountRequest request) {
        boolean created = service.ingest(request);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new Response(request.assertionId(), "LOCAL_OPEN_BANKING_MOCK", "v1"));
    }

    public record Response(UUID assertionId, String sourceCode, String sourceVersion) { }
}
