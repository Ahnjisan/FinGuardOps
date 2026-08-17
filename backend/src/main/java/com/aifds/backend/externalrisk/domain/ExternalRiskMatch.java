package com.aifds.backend.externalrisk.domain;

public record ExternalRiskMatch(
        ExternalRiskSubjectType subjectType,
        ExternalRiskType riskType,
        ExternalRiskReasonCode reasonCode
) {
}
