package com.aifds.backend.scenario.mock;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fds-mock-facts")
public class MockFactIntakeController {
    private final MockFactIntakeService service;

    public MockFactIntakeController(MockFactIntakeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MockFactResponse> ingest(@RequestBody MockFactRequest request) {
        MockFactIntakeService.IntakeResult result = service.ingest(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new MockFactResponse(result.factId(), "LOCAL_FDS_MOCK", "v1"));
    }

    public record MockFactResponse(UUID factId, String sourceCode, String sourceVersion) { }
}
