package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;

import java.time.Instant;
import java.util.UUID;

public record FraudCaseMutationResponse(
        UUID caseId,
        FraudCaseStatus caseStatus,
        FraudCaseFinalDisposition finalDisposition,
        String assigneeRef,
        Instant reviewStartedAt,
        Instant closedAt,
        Instant lastChangedAt,
        long concurrencyVersion,
        String traceId
) {
}
