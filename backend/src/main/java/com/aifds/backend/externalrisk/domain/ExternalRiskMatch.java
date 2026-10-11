package com.aifds.backend.externalrisk.domain;

public record ExternalRiskMatch(
        ExternalRiskSubjectType subjectType,
        ExternalRiskType riskType,
        ExternalRiskReasonCode reasonCode,
        String subjectDigest
) {
    public ExternalRiskMatch(ExternalRiskSubjectType subjectType,
                             ExternalRiskType riskType,
                             ExternalRiskReasonCode reasonCode) {
        this(subjectType, riskType, reasonCode, null);
    }
}
