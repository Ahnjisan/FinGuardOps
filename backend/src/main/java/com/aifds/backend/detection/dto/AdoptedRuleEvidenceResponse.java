package com.aifds.backend.detection.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AdoptedRuleEvidenceResponse(
        String ruleCode,
        String ruleVersion,
        String reasonCode,
        int scoreContribution,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer appliedContribution
) {
    public AdoptedRuleEvidenceResponse(String ruleCode, String ruleVersion,
            String reasonCode, int scoreContribution) {
        this(ruleCode, ruleVersion, reasonCode, scoreContribution, null);
    }
}
