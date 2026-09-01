package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record FraudCaseListItemResponse(
        UUID caseId,
        FraudCaseStatus caseStatus,
        FraudCaseFinalDisposition finalDisposition,
        String assigneeRef,
        long relatedTransactionCount,
        Instant createdAt,
        Instant lastChangedAt
) {
}
