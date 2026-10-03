package com.aifds.backend.detection.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdoptedDetectionResultItemResponse(
        UUID detectionResultId,
        int detectionResultVersion,
        String riskLevel,
        int riskScore,
        Instant analysisCompletedAt,
        String ruleSetVersion,
        String scoringPolicyVersion,
        List<AdoptedRuleEvidenceResponse> ruleEvidence
) {
}
