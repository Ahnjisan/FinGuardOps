package com.aifds.backend.transaction.policy;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.util.Objects;

public final class RiskResponseDecisionPolicy {

    public RiskResponseDecision decide(RiskLevel riskLevel) {
        RiskLevel sourceRiskLevel = Objects.requireNonNull(
                riskLevel,
                "riskLevel must not be null"
        );

        return switch (sourceRiskLevel) {
            case LOW -> new RiskResponseDecision(
                    RiskLevel.LOW,
                    TransactionProcessingStatus.APPROVED,
                    RiskResponseOutcome.APPROVED,
                    false
            );
            case MEDIUM -> new RiskResponseDecision(
                    RiskLevel.MEDIUM,
                    TransactionProcessingStatus.APPROVED,
                    RiskResponseOutcome.APPROVED_WITH_MONITORING,
                    false
            );
            case HIGH -> new RiskResponseDecision(
                    RiskLevel.HIGH,
                    TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                    RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                    true
            );
            case CRITICAL -> new RiskResponseDecision(
                    RiskLevel.CRITICAL,
                    TransactionProcessingStatus.HELD,
                    RiskResponseOutcome.HELD,
                    true
            );
        };
    }
}
