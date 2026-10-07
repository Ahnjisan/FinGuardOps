package com.aifds.backend.aireport.dlq;

import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.security.principal.CurrentAuditActorProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportDlqController {
    private final AiReportDlqService service;
    private final CurrentAuditActorProvider actors;

    public AiReportDlqController(AiReportDlqService service, CurrentAuditActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/api/v1/ai-report-dlq/{topicId}/{partition}/{offset}")
    public AiReportDlqDtos.Diagnostic inspect(@PathVariable String topicId,
            @PathVariable String partition, @PathVariable String offset,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        return service.inspect(topicId(topicId), partition(partition), offset(offset), traceId);
    }

    @PostMapping("/api/v1/ai-report-dlq/{topicId}/{partition}/{offset}/{action}")
    public ResponseEntity<AiReportDlqDtos.Diagnostic> decide(@PathVariable String topicId,
            @PathVariable String partition, @PathVariable String offset, @PathVariable String action,
            @RequestBody JsonNode body,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        if (!"replay".equals(action) && !"quarantine".equals(action))
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        if (body == null || !body.isObject() || body.size() != 1
                || !body.hasNonNull("observedCategory") || !body.get("observedCategory").isTextual())
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.decide(
                    topicId(topicId), partition(partition), offset(offset),
                    body.get("observedCategory").textValue(), "replay".equals(action),
                    actors.currentUserSubject(), traceId));
        } catch (DataAccessException failure) {
            Throwable root = failure.getMostSpecificCause();
            if (root instanceof java.sql.SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "23505".equals(sql.getSQLState())))
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_ACTION_NOT_ALLOWED");
            throw failure;
        }
    }

    private UUID topicId(String raw) {
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equals(raw)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }

    private int partition(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || !Integer.toString(value).equals(raw)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }

    private long offset(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value < 0 || !Long.toString(value).equals(raw)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }
}
