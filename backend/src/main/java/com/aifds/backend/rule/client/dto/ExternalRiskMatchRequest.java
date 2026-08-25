package com.aifds.backend.rule.client.dto;

import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record ExternalRiskMatchRequest(
        @JsonProperty(required = true) ExternalRiskSubjectType subjectType,
        @JsonProperty(value = "externalRiskType", required = true)
        ExternalRiskType riskType,
        @JsonProperty(required = true) ExternalRiskReasonCode reasonCode
) {

    public ExternalRiskMatchRequest {
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        Objects.requireNonNull(riskType, "riskType must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    }
}
