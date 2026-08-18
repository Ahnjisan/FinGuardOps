package com.aifds.backend.transaction.policy;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.util.Objects;

public record RiskResponseDecision(
        RiskLevel sourceRiskLevel,
        TransactionProcessingStatus targetTransactionStatus,
        RiskResponseOutcome riskResponseOutcome,
        boolean caseRequired
) {

    public RiskResponseDecision {
        Objects.requireNonNull(
                sourceRiskLevel,
                "sourceRiskLevel must not be null"
        );
        Objects.requireNonNull(
                targetTransactionStatus,
                "targetTransactionStatus must not be null"
        );
        Objects.requireNonNull(
                riskResponseOutcome,
                "riskResponseOutcome must not be null"
        );
    }
}
