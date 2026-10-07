package com.aifds.backend.aireport.dlq;

import java.util.UUID;

public final class AiReportDlqDtos {
    private AiReportDlqDtos() { }

    public record Diagnostic(UUID topicId, int partition, long offset, String failureCategory,
                             boolean sourceVerified, boolean sourceRecovered, UUID eventId,
                             UUID executionId, String executionStatus, boolean reportExists,
                             boolean attemptExists, String action, String dispatchStatus,
                             String startSource, Integer ackPartition, Long ackOffset,
                             boolean replayAllowed, String rejectionReason, String traceId) { }
}
