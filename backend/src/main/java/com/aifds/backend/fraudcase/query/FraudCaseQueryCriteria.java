package com.aifds.backend.fraudcase.query;

import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FraudCaseQueryCriteria(
        FraudCaseStatus caseStatus,
        FraudCaseFinalDisposition finalDisposition,
        String assigneeRef,
        Instant createdAtFrom,
        Instant createdAtTo,
        Instant lastChangedAtFrom,
        Instant lastChangedAtTo,
        UUID transactionId,
        int page,
        int size,
        Sort.Direction sortDirection
) {

    public FraudCaseQueryCriteria {
        Objects.requireNonNull(
                sortDirection,
                "sortDirection must not be null"
        );
    }
}
