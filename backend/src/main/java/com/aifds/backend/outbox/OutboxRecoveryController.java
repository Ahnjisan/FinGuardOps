package com.aifds.backend.outbox;

import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.security.principal.CurrentAuditActorProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
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
public class OutboxRecoveryController {
    private final OutboxRecoveryService service;
    private final CurrentAuditActorProvider actors;

    public OutboxRecoveryController(OutboxRecoveryService service, CurrentAuditActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/api/v1/ai-report-outbox/{executionId}")
    public OutboxRecoveryDtos.Diagnostic inspect(@PathVariable String executionId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        return service.inspect(parse(executionId), traceId);
    }

    @PostMapping("/api/v1/ai-report-outbox/{eventId}/requeue")
    public ResponseEntity<OutboxRecoveryDtos.Diagnostic> requeue(@PathVariable String eventId,
            @RequestBody JsonNode body,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId) {
        if (body == null || !body.isObject() || body.size() != 2
                || !body.hasNonNull("executionId") || !body.get("executionId").isTextual()
                || !body.hasNonNull("observedStatus")
                || !body.get("observedStatus").isTextual()) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        var request = new OutboxRecoveryDtos.RequeueRequest(parse(body.get("executionId").textValue()),
                body.get("observedStatus").textValue());
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requeue(parse(eventId),
                    request.executionId(), request.observedStatus(), actors.currentUserSubject(), traceId));
        } catch (DataAccessException failure) {
            Throwable root = failure.getMostSpecificCause();
            if (root instanceof java.sql.SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "23505".equals(sql.getSQLState()))) {
                throw new AiReportException(HttpStatus.CONFLICT, "OUTBOX_REQUEUE_NOT_ALLOWED");
            }
            throw failure;
        }
    }

    private UUID parse(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equals(raw) || id.version() != 4 || id.variant() != 2)
                throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }
}
