package com.aifds.backend.aireport.controller;

import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.service.AiReportService;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/ai-reports")
public class AiReportController {
    private final AiReportService service;

    public AiReportController(AiReportService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<AiReportDtos.RequestStatus> create(
            @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        var result = service.create(parse(caseId), key, parseBody(body), traceId);
        return ResponseEntity.status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .header("Location", result.response().resultLocation())
                .body(result.response());
    }

    @GetMapping("/current")
    public AiReportDtos.Current current(
            @PathVariable String caseId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        return service.current(parse(caseId), traceId);
    }

    private UUID parse(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equals(raw) || value.version() != 4 || value.variant() != 2) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }

    private AiReportDtos.CreateRequest parseBody(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("detectionResultVersion")
                || !body.get("detectionResultVersion").isInt()
                || body.size() > 2 ||
                java.util.stream.StreamSupport.stream(
                    java.util.Spliterators.spliteratorUnknownSize(body.fieldNames(), 0), false)
                    .anyMatch(name -> !name.equals("detectionResultVersion")
                            && !name.equals("regenerationReason"))) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        JsonNode reason = body.get("regenerationReason");
        if (reason != null && !reason.isNull() && !reason.isTextual()) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        return new AiReportDtos.CreateRequest(body.get("detectionResultVersion").intValue(),
                reason == null || reason.isNull() ? null : reason.textValue());
    }
}
