package com.aifds.backend.aireport.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
public class AiReportExecutionCreatedCodec {
    public static final int MAX_BYTES = 4096;
    private static final Set<String> FIELDS = Set.of("eventId", "eventType", "eventVersion",
            "occurredAt", "producer", "traceId", "correlationId", "causationId",
            "aggregateType", "executionId", "initiatingAiRequestId", "caseId",
            "detectionResultVersion", "promptVersion", "modelVersion", "executionStatus");
    private final ObjectMapper mapper;

    public AiReportExecutionCreatedCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(AiReportExecutionCreated event) {
        try {
            String json = mapper.writeValueAsString(event);
            decode(json, event.executionId().toString());
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI execution event cannot be encoded", exception);
        }
    }

    public AiReportExecutionCreated decode(String json, String key) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new InvalidEventException("event size");
        }
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = mapper.readTree(parser);
            if (tree == null || !tree.isObject() || tree.size() != FIELDS.size()) {
                throw new InvalidEventException("event fields");
            }
            tree.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field) || tree.get(field).isNull()) {
                    throw new InvalidEventException("event fields");
                }
            });
            AiReportExecutionCreated event = mapper.treeToValue(tree, AiReportExecutionCreated.class);
            if (!"AiReportExecutionCreated".equals(event.eventType()) || event.eventVersion() != 1
                    || !"SPRING_BOOT".equals(event.producer())
                    || !"AiReportExecution".equals(event.aggregateType())
                    || !"PENDING".equals(event.executionStatus())
                    || event.detectionResultVersion() < 1 || event.occurredAt() == null
                    || !valid(event.eventId()) || !valid(event.executionId())
                    || !valid(event.initiatingAiRequestId()) || !valid(event.caseId())
                    || !valid(event.correlationId()) || !valid(event.causationId())
                    || !event.causationId().equals(event.initiatingAiRequestId())
                    || !event.executionId().toString().equals(key)
                    || blank(event.traceId()) || blank(event.promptVersion())
                    || blank(event.modelVersion())) {
                throw new InvalidEventException("event contract");
            }
            return event;
        } catch (IOException exception) {
            throw new InvalidEventException("event json", exception);
        }
    }

    private boolean valid(UUID id) {
        return id != null && id.version() == 4 && id.variant() == 2;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static final class InvalidEventException extends RuntimeException {
        public InvalidEventException(String reason) { super(reason); }
        public InvalidEventException(String reason, Throwable cause) { super(reason, cause); }
    }
}
