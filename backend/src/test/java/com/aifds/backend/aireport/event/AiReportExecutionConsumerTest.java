package com.aifds.backend.aireport.event;

import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.service.AiReportWorker;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiReportExecutionConsumerTest {
    private final AiReportExecutionCreatedCodec codec =
            new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules());
    private final AiReportExecutionRepository executions = mock(AiReportExecutionRepository.class);
    private final AiReportWorker worker = mock(AiReportWorker.class);
    private final AiReportKafkaMetrics metrics = mock(AiReportKafkaMetrics.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final AiReportExecutionConsumer consumer =
            new AiReportExecutionConsumer(codec, executions, worker, metrics);
    private final AiReportExecutionCreated event = AiReportExecutionCreated.newExecution(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            "prompt-1", "model-1", "trace-test-001");

    @Test
    void startsOnlyValidatedExecution() {
        when(executions.matches(event)).thenReturn(true);
        when(worker.runExecution(event.executionId())).thenReturn(AiReportWorker.StartResult.STARTED);
        consumer.consume(codec.encode(event), event.executionId().toString(), ack);
        verify(worker).runExecution(event.executionId());
        verify(ack).acknowledge();
    }

    @Test
    void busyAndTerminalDeliveryAreAcknowledgedWithoutNewProviderCall() {
        when(executions.matches(event)).thenReturn(true);
        when(worker.runExecution(event.executionId())).thenReturn(AiReportWorker.StartResult.NOT_CLAIMED);
        when(executions.status(event.executionId())).thenReturn(Optional.of(AiReportStatus.PENDING),
                Optional.of(AiReportStatus.FAILED));
        String payload = codec.encode(event);
        consumer.consume(payload, event.executionId().toString(), ack);
        consumer.consume(payload, event.executionId().toString(), ack);
        verify(metrics).busy();
        verify(metrics).duplicate();
        verify(ack, times(2)).acknowledge();
    }

    @Test
    void poisonNeverReachesWorkerOrOffsetAck() {
        assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                () -> consumer.consume(codec.encode(event), UUID.randomUUID().toString(), ack));
        when(executions.matches(event)).thenReturn(false);
        assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                () -> consumer.consume(codec.encode(event), event.executionId().toString(), ack));
        verifyNoInteractions(worker);
        verifyNoInteractions(ack);
    }
}
