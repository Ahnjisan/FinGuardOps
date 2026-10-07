package com.aifds.backend.aireport.dlq;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.outbox.OutboxRecoveryRepository;
import com.aifds.backend.outbox.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportDlqService {
    private final AiReportDlqReader reader;
    private final AiReportDlqRepository actions;
    private final OutboxRepository outbox;
    private final OutboxRecoveryRepository recovery;
    private final AiReportExecutionRepository executions;
    private final AiReportExecutionCreatedCodec codec;

    public AiReportDlqService(AiReportDlqReader reader, AiReportDlqRepository actions,
                              OutboxRepository outbox, OutboxRecoveryRepository recovery,
                              AiReportExecutionRepository executions, AiReportExecutionCreatedCodec codec) {
        this.reader = reader;
        this.actions = actions;
        this.outbox = outbox;
        this.recovery = recovery;
        this.executions = executions;
        this.codec = codec;
    }

    @Transactional(readOnly = true)
    public AiReportDlqDtos.Diagnostic inspect(UUID topicId, int partition, long offset, String traceId) {
        return diagnostic(topicId, partition, offset, traceId, false);
    }

    @Transactional
    public AiReportDlqDtos.Diagnostic decide(UUID topicId, int partition, long offset,
                                              String observedCategory, boolean replay,
                                              UUID actor, String traceId) {
        var read = reader.read(topicId, partition, offset);
        if (!read.metadata().category().equals(observedCategory)) conflict();
        if (actions.action(topicId, partition, offset).isPresent()) conflict();
        Evaluation evaluation = evaluate(read, replay);
        if (replay && evaluation.reason != null) conflict();
        String action = replay ? "REPLAY" : "QUARANTINE";
        long id = actions.insertAction(topicId, partition, offset, action,
                read.metadata().category(), evaluation.eventId, evaluation.executionId,
                actor, traceId);
        if (replay) actions.insertIntent(id, evaluation.eventId, evaluation.executionId);
        return diagnostic(topicId, partition, offset, traceId, false);
    }

    private AiReportDlqDtos.Diagnostic diagnostic(UUID topicId, int partition, long offset,
                                                   String traceId, boolean lock) {
        var read = reader.read(topicId, partition, offset);
        Evaluation evaluation = evaluate(read, lock);
        var action = actions.action(topicId, partition, offset).orElse(null);
        String startSource = evaluation.executionId == null ? null
                : actions.startSource(evaluation.executionId).orElse(null);
        boolean allowed = evaluation.reason == null && action == null;
        String reason = action != null ? "ALREADY_DECIDED" : evaluation.reason;
        return new AiReportDlqDtos.Diagnostic(topicId, partition, offset,
                read.metadata().category(), read.sourceVerified(), evaluation.sourceRecovered,
                evaluation.eventId, evaluation.executionId, evaluation.executionStatus,
                evaluation.reportExists, evaluation.attemptExists,
                action == null ? null : action.action(),
                action == null ? null : action.dispatchStatus(), startSource,
                action == null ? null : action.ackPartition(),
                action == null ? null : action.ackOffset(), allowed, reason, traceId);
    }

    private Evaluation evaluate(AiReportDlqReader.ReadRecord read, boolean lock) {
        AiReportDlqMetadata metadata = read.metadata();
        String reason = !"PRE_CLAIM_TRANSIENT".equals(metadata.category())
                ? "FAILURE_NOT_REPLAYABLE" : null;
        if (!read.sourceVerified()) reason = "SOURCE_UNVERIFIED";
        boolean sourceRecovered = false;
        if (read.sourceVerified()) {
            sourceRecovered = reader.sourceRecovered(metadata);
            if (!sourceRecovered) reason = "SOURCE_OFFSET_NOT_RECOVERED";
        }
        AiReportExecutionCreated event;
        try {
            event = codec.decode(read.record().value(), read.record().key());
        } catch (RuntimeException invalid) {
            return new Evaluation(null, null, null, false, false,
                    sourceRecovered, "EVENT_INVALID");
        }
        UUID eventId = event.eventId();
        UUID executionId = event.executionId();
        var row = outbox.recoveryRow(eventId, lock).orElse(null);
        if (row == null || !row.executionId().equals(executionId)
                || !"PUBLISHED".equals(row.status()) || row.claimToken() != null)
            return new Evaluation(eventId, executionId, null, false, false,
                    sourceRecovered, "OUTBOX_MISMATCH");
        AiReportExecutionCreated canonical;
        try {
            canonical = codec.decode(row.payload(), executionId.toString());
        } catch (RuntimeException invalid) {
            return new Evaluation(eventId, executionId, null, false, false,
                    sourceRecovered, "OUTBOX_EVENT_INVALID");
        }
        if (!canonical.equals(event) || !executions.matches(event))
            reason = "EVENT_RELATIONSHIP_MISMATCH";
        var execution = recovery.execution(executionId, lock).orElse(null);
        if (execution == null) return new Evaluation(eventId, executionId, null,
                false, false, sourceRecovered, "EXECUTION_MISSING");
        List<OutboxRecoveryRepository.RequestRow> requests = recovery.requests(execution.id(), lock);
        boolean report = recovery.reportExists(execution.id());
        boolean attempt = recovery.attemptExists(execution.id());
        if (!"PENDING".equals(execution.status()) || execution.leased())
            reason = "EXECUTION_NOT_PENDING";
        else if (execution.failureCode() != null) reason = "EXECUTION_FAILURE_RECORDED";
        else if (report) reason = "REPORT_EXISTS";
        else if (attempt) reason = "ATTEMPT_EXISTS";
        else if (requests.isEmpty() || requests.stream().filter(q -> !q.shared()).count() != 1
                || requests.stream().anyMatch(q -> !"PENDING".equals(q.status())
                    || q.cacheHit() || q.reportLinked() || q.casePk() != execution.casePk()
                    || q.detectionResultVersion() != execution.detectionResultVersion()
                    || !q.promptVersion().equals(execution.promptVersion())
                    || !q.modelVersion().equals(execution.modelVersion()))
                || requests.stream().filter(q -> !q.shared()
                    && q.aiRequestId().equals(event.initiatingAiRequestId())
                    && q.traceId().equals(event.traceId())).count() != 1
                || !event.caseId().equals(execution.caseId()))
            reason = "REQUEST_MISMATCH";
        return new Evaluation(eventId, executionId, execution.status(), report,
                attempt, sourceRecovered, reason);
    }

    private void conflict() {
        throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_ACTION_NOT_ALLOWED");
    }

    private record Evaluation(UUID eventId, UUID executionId, String executionStatus,
                              boolean reportExists, boolean attemptExists,
                              boolean sourceRecovered, String reason) { }
}
