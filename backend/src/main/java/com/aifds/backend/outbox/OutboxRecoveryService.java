package com.aifds.backend.outbox;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.exception.AiReportException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OutboxRecoveryService {
    private final OutboxRepository outbox;
    private final OutboxRecoveryRepository recovery;
    private final AiReportExecutionCreatedCodec codec;

    public OutboxRecoveryService(OutboxRepository outbox, OutboxRecoveryRepository recovery,
                                 AiReportExecutionCreatedCodec codec) {
        this.outbox = outbox;
        this.recovery = recovery;
        this.codec = codec;
    }

    @Transactional(readOnly = true)
    public OutboxRecoveryDtos.Diagnostic inspect(UUID executionId, String traceId) {
        UUID eventId = outbox.eventIdForExecution(executionId).orElseThrow(() -> missing());
        return diagnostic(eventId, traceId, false);
    }

    @Transactional
    public OutboxRecoveryDtos.Diagnostic requeue(UUID eventId, UUID observedExecutionId,
                                                  String observedStatus, UUID actorId,
                                                  String traceId) {
        var row = outbox.recoveryRow(eventId, true).orElseThrow(() -> missing());
        if (!"BLOCKED".equals(observedStatus)) conflict();
        if (!row.executionId().equals(observedExecutionId)) conflict();
        var execution = recovery.execution(row.executionId(), true).orElseThrow(() -> conflict());
        var requests = recovery.requests(execution.id(), true);
        boolean reportExists = recovery.reportExists(execution.id());
        boolean attemptExists = recovery.attemptExists(execution.id());
        boolean previouslyRequeued = recovery.alreadyRequeued(eventId);
        String reason = rejection(row, execution, requests, reportExists, attemptExists,
                previouslyRequeued);
        if (reason != null) conflict();
        if (!outbox.requeueBlocked(row.id(), eventId, row.executionId())) conflict();
        recovery.record(eventId, row.executionId(), actorId, traceId);
        return diagnostic(eventId, traceId, false);
    }

    private OutboxRecoveryDtos.Diagnostic diagnostic(UUID eventId, String traceId, boolean lock) {
        var row = outbox.recoveryRow(eventId, lock).orElseThrow(() -> missing());
        var execution = recovery.execution(row.executionId(), lock).orElse(null);
        if (execution == null) {
            return new OutboxRecoveryDtos.Diagnostic(eventId, row.executionId(), row.status(),
                    row.attemptCount(), safeFailure(row.failureCode()), null, null, List.of(),
                    false, false, false, "EXECUTION_MISSING", false, traceId);
        }
        var requests = recovery.requests(execution.id(), lock);
        boolean reportExists = recovery.reportExists(execution.id());
        boolean attemptExists = recovery.attemptExists(execution.id());
        boolean previouslyRequeued = recovery.alreadyRequeued(eventId);
        String reason = rejection(row, execution, requests, reportExists, attemptExists,
                previouslyRequeued);
        return new OutboxRecoveryDtos.Diagnostic(eventId, row.executionId(), row.status(),
                row.attemptCount(), safeFailure(row.failureCode()), execution.status(),
                safeExecutionFailure(execution.failureCode()),
                requests.stream().map(q -> new OutboxRecoveryDtos.RequestState(
                        q.aiRequestId(), q.status())).toList(), reportExists, attemptExists,
                reason == null, reason, previouslyRequeued, traceId);
    }

    private String rejection(OutboxRepository.RecoveryRow row,
                             OutboxRecoveryRepository.ExecutionRow execution,
                             List<OutboxRecoveryRepository.RequestRow> requests,
                             boolean reportExists, boolean attemptExists,
                             boolean previouslyRequeued) {
        if (row.claimToken() != null || row.leaseUntil() != null)
            return "OUTBOX_CLAIMED";
        if (!"BLOCKED".equals(row.status())) return "OUTBOX_NOT_BLOCKED";
        if (row.publishedAt() != null) return "OUTBOX_STATE_MISMATCH";
        if (previouslyRequeued) return "ALREADY_REQUEUED";
        if (!"PENDING".equals(execution.status()) || execution.leased())
            return "EXECUTION_NOT_PENDING";
        if (reportExists) return "REPORT_EXISTS";
        if (attemptExists) return "ATTEMPT_EXISTS";
        if (requests.isEmpty()) return "REQUEST_MISSING";
        if (requests.stream().anyMatch(q -> !"PENDING".equals(q.status())
                || q.cacheHit() || q.reportLinked()
                || q.casePk() != execution.casePk()
                || q.detectionResultVersion() != execution.detectionResultVersion()
                || !q.promptVersion().equals(execution.promptVersion())
                || !q.modelVersion().equals(execution.modelVersion()))) return "REQUEST_MISMATCH";
        if (!"AiReportExecutionCreated".equals(row.eventType()) || row.eventVersion() != 1)
            return "EVENT_INVALID";
        try {
            AiReportExecutionCreated event = codec.decode(row.payload(), row.executionId().toString());
            if (!event.eventId().equals(row.eventId())
                    || !event.executionId().equals(execution.executionId())
                    || !event.caseId().equals(execution.caseId())
                    || event.detectionResultVersion() != execution.detectionResultVersion()
                    || !event.promptVersion().equals(execution.promptVersion())
                    || !event.modelVersion().equals(execution.modelVersion())
                    || requests.stream().filter(q -> !q.shared()
                            && q.aiRequestId().equals(event.initiatingAiRequestId())
                            && q.traceId().equals(event.traceId())).count() != 1
                    || requests.stream().filter(q -> !q.shared()).count() != 1) {
                return "EVENT_RELATIONSHIP_MISMATCH";
            }
        } catch (RuntimeException invalid) {
            return "EVENT_INVALID";
        }
        return null;
    }

    private String safeFailure(String code) {
        return code != null && java.util.Set.of("PUBLISH_FAILED", "PUBLISH_INTERRUPTED",
                "PUBLISH_ACK_UNCONFIRMED").contains(code) ? code : null;
    }

    private String safeExecutionFailure(String code) {
        return "WORKER_INTERRUPTED".equals(code) ? code : null;
    }

    private AiReportException missing() {
        return new AiReportException(HttpStatus.NOT_FOUND, "AI_REPORT_OUTBOX_NOT_FOUND");
    }

    private AiReportException conflict() {
        throw new AiReportException(HttpStatus.CONFLICT, "OUTBOX_REQUEUE_NOT_ALLOWED");
    }
}
