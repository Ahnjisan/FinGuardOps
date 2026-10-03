package com.aifds.backend.detection.dto;

public record AdoptedRuleEvidenceResponse(
        String ruleCode,
        String ruleVersion,
        String reasonCode,
        int scoreContribution
) {
}
