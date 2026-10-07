package com.aifds.backend.outbox;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.exception.AiReportException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxRecoveryServiceTest {
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final OutboxRecoveryRepository recovery = mock(OutboxRecoveryRepository.class);
    private final AiReportExecutionCreatedCodec codec = mock(AiReportExecutionCreatedCodec.class);
    private final OutboxRecoveryService service = new OutboxRecoveryService(outbox, recovery, codec);

    @Test
    void terminalExecutionNeverMutatesOutboxOrAudit() {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(outbox.recoveryRow(eventId, true)).thenReturn(Optional.of(row(eventId, executionId, "BLOCKED")));
        when(recovery.execution(executionId, true)).thenReturn(Optional.of(execution(executionId, "FAILED")));
        AiReportException error = assertThrows(AiReportException.class,
                () -> service.requeue(eventId, executionId, "BLOCKED", UUID.randomUUID(),
                        "trace-test-001"));
        assertEquals(409, error.status().value());
        verify(outbox, never()).requeueBlocked(anyLong(), any(), any());
        verify(recovery, never()).record(any(), any(), any(), any());
    }

    @Test
    void observedStatusMismatchNeverMutatesDatabase() {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(outbox.recoveryRow(eventId, true)).thenReturn(Optional.of(row(eventId, executionId, "BLOCKED")));
        assertEquals(409, assertThrows(AiReportException.class,
                () -> service.requeue(eventId, executionId, "PENDING",
                        UUID.randomUUID(), "trace-test-001")).status().value());
        verify(outbox, never()).requeueBlocked(anyLong(), any(), any());
        verifyNoInteractions(recovery, codec);
    }

    @Test
    void missingEventWinsOverMismatchedObservation() {
        UUID eventId = UUID.randomUUID();
        when(outbox.recoveryRow(eventId, true)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(AiReportException.class,
                () -> service.requeue(eventId, UUID.randomUUID(), "PENDING",
                        UUID.randomUUID(), "trace-test-001")).status().value());
        verify(outbox, never()).requeueBlocked(anyLong(), any(), any());
        verifyNoInteractions(recovery, codec);
    }

    @Test
    void eligibleEventChangesOnlyOneOutboxRowAndRecordsActor() {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        var event = AiReportExecutionCreated.newExecution(executionId, requestId, caseId,
                1, "prompt-1", "model-1", "trace-origin-001");
        event = new AiReportExecutionCreated(eventId, event.eventType(), event.eventVersion(),
                event.occurredAt(), event.producer(), event.traceId(), event.correlationId(),
                event.causationId(), event.aggregateType(), event.executionId(),
                event.initiatingAiRequestId(), event.caseId(), event.detectionResultVersion(),
                event.promptVersion(), event.modelVersion(), event.executionStatus());
        when(outbox.recoveryRow(eventId, true)).thenReturn(Optional.of(row(eventId, executionId, "BLOCKED")));
        when(outbox.recoveryRow(eventId, false)).thenReturn(Optional.of(row(eventId, executionId, "PENDING")));
        when(recovery.execution(executionId, true)).thenReturn(Optional.of(execution(executionId, "PENDING", caseId)));
        when(recovery.execution(executionId, false)).thenReturn(Optional.of(execution(executionId, "PENDING", caseId)));
        when(recovery.requests(7, true)).thenReturn(List.of(request(requestId)));
        when(recovery.requests(7, false)).thenReturn(List.of(request(requestId)));
        when(codec.decode("{}", executionId.toString())).thenReturn(event);
        when(outbox.requeueBlocked(3, eventId, executionId)).thenReturn(true);
        var result = service.requeue(eventId, executionId, "BLOCKED", actorId, "trace-test-001");
        assertEquals("PENDING", result.outboxStatus());
        verify(outbox, times(1)).requeueBlocked(3, eventId, executionId);
        verify(recovery, times(1)).record(eventId, executionId, actorId, "trace-test-001");
    }

    private OutboxRepository.RecoveryRow row(UUID eventId, UUID executionId, String status) {
        return new OutboxRepository.RecoveryRow(3, eventId, executionId,
                "AiReportExecutionCreated", 1, "{}", status, 10,
                "PUBLISH_FAILED", null, null, null);
    }

    private OutboxRecoveryRepository.ExecutionRow execution(UUID executionId, String status) {
        return execution(executionId, status, UUID.randomUUID());
    }

    private OutboxRecoveryRepository.ExecutionRow execution(UUID executionId, String status, UUID caseId) {
        return new OutboxRecoveryRepository.ExecutionRow(7, executionId, status, null,
                false, 1, "prompt-1", "model-1", caseId, 11);
    }

    private OutboxRecoveryRepository.RequestRow request(UUID requestId) {
        return new OutboxRecoveryRepository.RequestRow(requestId, "PENDING", false,
                false, false, "trace-origin-001", 1, "prompt-1", "model-1", 11);
    }
}
