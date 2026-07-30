package com.aifds.backend.transaction.entity;

import com.aifds.backend.detection.entity.RiskLevel;

public enum RiskResponseOutcome {
    APPROVED(RiskLevel.LOW),
    APPROVED_WITH_MONITORING(RiskLevel.MEDIUM),
    ADDITIONAL_AUTH_REQUIRED(RiskLevel.HIGH),
    HELD(RiskLevel.CRITICAL);

    private final RiskLevel riskLevel;

    RiskResponseOutcome(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean supports(RiskLevel candidate) {
        return riskLevel == candidate;
    }
}
