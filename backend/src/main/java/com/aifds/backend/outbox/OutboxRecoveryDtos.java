package com.aifds.backend.outbox;

import java.util.List;
import java.util.UUID;

public final class OutboxRecoveryDtos {
    private OutboxRecoveryDtos() { }

    public record RequestState(UUID aiRequestId, String status) { }

    public record Diagnostic(UUID eventId, UUID executionId, String outboxStatus,
                             int attemptCount, String failureCode, String executionStatus,
                             String executionFailureCode, List<RequestState> requests,
                             boolean reportExists, boolean attemptExists,
                             boolean requeueAllowed, String rejectionReason,
                             boolean previouslyRequeued, String traceId) { }

    public record RequeueRequest(UUID executionId, String observedStatus) { }
}
