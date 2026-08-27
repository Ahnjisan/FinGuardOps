package com.aifds.backend.externalrisk.client.dto;

import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;

public record ExternalRiskHttpMatchResponse(
        ExternalRiskSubjectType subjectType,
        ExternalRiskType riskType,
        ExternalRiskReasonCode reasonCode
) {
}
