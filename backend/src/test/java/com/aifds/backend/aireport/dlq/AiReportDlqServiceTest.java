package com.aifds.backend.aireport.dlq;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.outbox.OutboxRecoveryRepository;
import com.aifds.backend.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiReportDlqServiceTest {
    private final AiReportDlqReader reader = mock(AiReportDlqReader.class);
    private final AiReportDlqRepository actions = mock(AiReportDlqRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final OutboxRecoveryRepository recovery = mock(OutboxRecoveryRepository.class);
    private final AiReportExecutionRepository executions = mock(AiReportExecutionRepository.class);
    private final AiReportExecutionCreatedCodec codec =
            new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules());
    private final AiReportDlqService service = new AiReportDlqService(reader, actions, outbox,
            recovery, executions, codec);
    private final UUID topicId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @Test
    void acceptsOnlyVerifiedPreClaimEventAndWritesOneIntent() {
        var event = AiReportExecutionCreated.newExecution(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "prompt-1", "model-1", "trace-one");
        var record = new ConsumerRecord<>("dlq", 0, 3L, event.executionId().toString(), codec.encode(event));
        var metadata = new AiReportDlqMetadata("PRE_CLAIM_TRANSIENT", UUID.randomUUID(),
                "source", 0, 2, "worker");
        when(reader.read(topicId, 0, 3)).thenReturn(new AiReportDlqReader.ReadRecord(record, metadata, true));
        when(reader.sourceRecovered(metadata)).thenReturn(true);
        when(outbox.recoveryRow(event.eventId(), true)).thenReturn(Optional.of(row(event)));
        when(outbox.recoveryRow(event.eventId(), false)).thenReturn(Optional.of(row(event)));
        when(executions.matches(event)).thenReturn(true);
        var execution = new OutboxRecoveryRepository.ExecutionRow(7, event.executionId(),
                "PENDING", null, false, 1, "prompt-1", "model-1", event.caseId(), 5);
        when(recovery.execution(event.executionId(), true)).thenReturn(Optional.of(execution));
        when(recovery.execution(event.executionId(), false)).thenReturn(Optional.of(execution));
        when(recovery.requests(7, true)).thenReturn(List.of(request(event)));
        when(recovery.requests(7, false)).thenReturn(List.of(request(event)));
        when(actions.action(topicId, 0, 3)).thenReturn(Optional.empty(),
                Optional.of(new AiReportDlqRepository.ActionRow(10, "REPLAY",
                        "PRE_CLAIM_TRANSIENT", event.eventId(), event.executionId(),
                        "PENDING", null, null)));
        when(actions.insertAction(eq(topicId), eq(0), eq(3L), eq("REPLAY"),
                eq("PRE_CLAIM_TRANSIENT"), eq(event.eventId()), eq(event.executionId()),
                eq(actor), eq("trace-action"))).thenReturn(10L);

        var response = service.decide(topicId, 0, 3, "PRE_CLAIM_TRANSIENT", true,
                actor, "trace-action");
        assertEquals("REPLAY", response.action());
        assertEquals("PENDING", response.dispatchStatus());
        verify(actions).insertIntent(10, event.eventId(), event.executionId());
    }

    @Test
    void legacyUnknownCanBeQuarantinedButNeverReplayed() {
        var event = AiReportExecutionCreated.newExecution(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "prompt-1", "model-1", "trace-one");
        var record = new ConsumerRecord<>("dlq", 0, 3L, event.executionId().toString(), codec.encode(event));
        var metadata = new AiReportDlqMetadata("UNKNOWN", null, "", -1, -1, "");
        when(reader.read(topicId, 0, 3)).thenReturn(new AiReportDlqReader.ReadRecord(record, metadata, false));
        assertEquals(409, assertThrows(AiReportException.class,
                () -> service.decide(topicId, 0, 3, "UNKNOWN", true, actor, "trace-action"))
                .status().value());
        verify(actions, never()).insertIntent(anyLong(), any(), any());
        service.decide(topicId, 0, 3, "UNKNOWN", false, actor, "trace-action");
        verify(actions).insertAction(eq(topicId), eq(0), eq(3L), eq("QUARANTINE"),
                eq("UNKNOWN"), eq(event.eventId()), eq(event.executionId()), eq(actor),
                eq("trace-action"));
    }

    @Test
    void nullPayloadPoisonCanBeQuarantinedWithoutInventingAStringValue() {
        var record = new ConsumerRecord<String, String>("dlq", 0, 3L, null, null);
        when(reader.read(topicId, 0, 3)).thenReturn(new AiReportDlqReader.ReadRecord(record,
                new AiReportDlqMetadata("UNKNOWN", null, "", -1, -1, ""), false));
        service.decide(topicId, 0, 3, "UNKNOWN", false, actor, "trace-action");
        verify(actions).insertAction(eq(topicId), eq(0), eq(3L), eq("QUARANTINE"),
                eq("UNKNOWN"), isNull(), isNull(), eq(actor), eq("trace-action"));
        verify(actions, never()).insertIntent(anyLong(), any(), any());
    }

    @Test
    void terminalGeneratingAndProviderUncertaintyCannotBeReplayed() {
        var event = AiReportExecutionCreated.newExecution(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "prompt-1", "model-1", "trace-one");
        for (String status : List.of("GENERATING", "COMPLETED", "FALLBACK_COMPLETED", "FAILED")) {
            candidate(event, status, false, false, List.of(request(event)), true);
            assertEquals("EXECUTION_NOT_PENDING", service.inspect(topicId, 0, 3,
                    "trace-action").rejectionReason());
            assertEquals(409, assertThrows(AiReportException.class,
                    () -> service.decide(topicId, 0, 3, "PRE_CLAIM_TRANSIENT", true,
                            actor, "trace-action")).status().value());
        }
        candidate(event, "PENDING", true, false, List.of(request(event)), true);
        assertEquals("REPORT_EXISTS", service.inspect(topicId, 0, 3,
                "trace-action").rejectionReason());
        candidate(event, "PENDING", false, true, List.of(request(event)), true);
        assertEquals("ATTEMPT_EXISTS", service.inspect(topicId, 0, 3,
                "trace-action").rejectionReason());
        candidate(event, "PENDING", false, false, List.of(request(event)), true);
        when(recovery.execution(event.executionId(), false)).thenReturn(Optional.of(
                new OutboxRecoveryRepository.ExecutionRow(7, event.executionId(), "PENDING",
                        "WORKER_INTERRUPTED", false, 1, "prompt-1", "model-1", event.caseId(), 5)));
        assertEquals("EXECUTION_FAILURE_RECORDED", service.inspect(topicId, 0, 3,
                "trace-action").rejectionReason());
        verify(actions, never()).insertIntent(anyLong(), any(), any());
    }

    @Test
    void missingRequestRelationshipAndUnrecoveredSourceAreRejected() {
        var event = AiReportExecutionCreated.newExecution(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "prompt-1", "model-1", "trace-one");
        candidate(event, "PENDING", false, false, List.of(), true);
        assertEquals("REQUEST_MISMATCH", service.inspect(topicId, 0, 3,
                "trace-action").rejectionReason());
        candidate(event, "PENDING", false, false, List.of(request(event)), false);
        assertEquals("SOURCE_OFFSET_NOT_RECOVERED", service.inspect(topicId, 0, 3,
                "trace-action").rejectionReason());
    }

    private void candidate(AiReportExecutionCreated event, String status,
                           boolean report, boolean attempt,
                           List<OutboxRecoveryRepository.RequestRow> requests,
                           boolean recovered) {
        reset(reader, actions, outbox, recovery, executions);
        var metadata = new AiReportDlqMetadata("PRE_CLAIM_TRANSIENT", UUID.randomUUID(),
                "source", 0, 2, "worker");
        var record = new ConsumerRecord<>("dlq", 0, 3L, event.executionId().toString(),
                codec.encode(event));
        when(reader.read(topicId, 0, 3)).thenReturn(new AiReportDlqReader.ReadRecord(
                record, metadata, true));
        when(reader.sourceRecovered(metadata)).thenReturn(recovered);
        when(outbox.recoveryRow(event.eventId(), false)).thenReturn(Optional.of(row(event)));
        when(outbox.recoveryRow(event.eventId(), true)).thenReturn(Optional.of(row(event)));
        when(executions.matches(event)).thenReturn(true);
        var execution = new OutboxRecoveryRepository.ExecutionRow(7, event.executionId(),
                status, null, false, 1, "prompt-1", "model-1", event.caseId(), 5);
        when(recovery.execution(event.executionId(), false)).thenReturn(Optional.of(execution));
        when(recovery.execution(event.executionId(), true)).thenReturn(Optional.of(execution));
        when(recovery.requests(7, false)).thenReturn(requests);
        when(recovery.requests(7, true)).thenReturn(requests);
        when(recovery.reportExists(7)).thenReturn(report);
        when(recovery.attemptExists(7)).thenReturn(attempt);
    }

    private OutboxRepository.RecoveryRow row(AiReportExecutionCreated event) {
        return new OutboxRepository.RecoveryRow(9, event.eventId(), event.executionId(),
                event.eventType(), 1, codec.encode(event), "PUBLISHED", 1,
                null, null, null, java.time.Instant.now());
    }

    private OutboxRecoveryRepository.RequestRow request(AiReportExecutionCreated event) {
        return new OutboxRecoveryRepository.RequestRow(event.initiatingAiRequestId(),
                "PENDING", false, false, false, event.traceId(), 1,
                "prompt-1", "model-1", 5);
    }
}
