package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.entity.FraudCaseStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FraudCaseLinkResult(
        UUID caseId,
        UUID transactionId,
        FraudCaseStatus caseStatus,
        Instant linkedAt,
        boolean newlyCreated
) {

    public FraudCaseLinkResult {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(
                transactionId,
                "transactionId must not be null"
        );
        Objects.requireNonNull(
                caseStatus,
                "caseStatus must not be null"
        );
        Objects.requireNonNull(linkedAt, "linkedAt must not be null");
    }
}
