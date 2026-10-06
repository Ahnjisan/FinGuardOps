package com.aifds.backend.aireport.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record AiReportExecutionCreated(UUID eventId, String eventType, int eventVersion,
                                       @JsonFormat(shape = JsonFormat.Shape.STRING)
                                       Instant occurredAt, String producer, String traceId,
                                       UUID correlationId, UUID causationId,
                                       String aggregateType, UUID executionId,
                                       UUID initiatingAiRequestId, UUID caseId,
                                       int detectionResultVersion, String promptVersion,
                                       String modelVersion, String executionStatus) {
    public static AiReportExecutionCreated newExecution(UUID executionId, UUID requestId,
                                                        UUID caseId, int version, String prompt,
                                                        String model, String traceId) {
        return new AiReportExecutionCreated(UUID.randomUUID(), "AiReportExecutionCreated", 1,
                Instant.now().truncatedTo(ChronoUnit.MICROS), "SPRING_BOOT", traceId,
                UUID.randomUUID(), requestId,
                "AiReportExecution", executionId, requestId, caseId, version, prompt,
                model, "PENDING");
    }
}
