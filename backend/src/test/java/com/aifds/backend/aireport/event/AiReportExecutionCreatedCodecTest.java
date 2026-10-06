package com.aifds.backend.aireport.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiReportExecutionCreatedCodecTest {
    private final AiReportExecutionCreatedCodec codec =
            new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void exactV1RoundTripAndKey() {
        var event = event();
        assertEquals(0, event.occurredAt().getNano() % 1000);
        String encoded = codec.encode(event);
        assertEquals(event, codec.decode(encoded, event.executionId().toString()));
        assertNotEquals(event.eventId(), event.executionId());
    }

    @Test
    void rejectsPoisonBeforeWorker() {
        var event = event();
        String encoded = codec.encode(event);
        assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                () -> codec.decode(encoded, UUID.randomUUID().toString()));
        assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                () -> codec.decode(encoded.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                        event.executionId().toString()));
        assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                () -> codec.decode(encoded.replaceFirst("\\{", "{\"unexpected\":true,"),
                        event.executionId().toString()));
        assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                () -> codec.decode("{" + "x".repeat(4096) + "}", event.executionId().toString()));
    }

    private AiReportExecutionCreated event() {
        return AiReportExecutionCreated.newExecution(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "prompt-1", "model-1", "trace-test-001");
    }
}
