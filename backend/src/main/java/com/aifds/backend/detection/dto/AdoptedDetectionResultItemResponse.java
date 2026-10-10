package com.aifds.backend.detection.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AdoptedDetectionResultItemResponse(
        UUID detectionResultId,
        int detectionResultVersion,
        String riskLevel,
        int riskScore,
        Instant analysisCompletedAt,
        String ruleSetVersion,
        String scoringPolicyVersion,
        List<AdoptedRuleEvidenceResponse> ruleEvidence,
        int ruleScore,
        Integer mlContribution,
        String mlStatus,
        String modelVersion,
        String mlFeatureVersion,
        String modelSha256,
        List<AdoptedMlEvidenceResponse> mlEvidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Scn003EvidenceResponse scn003Evidence
) {
    public AdoptedDetectionResultItemResponse(UUID detectionResultId, int detectionResultVersion,
            String riskLevel, int riskScore, Instant analysisCompletedAt, String ruleSetVersion,
            String scoringPolicyVersion, List<AdoptedRuleEvidenceResponse> ruleEvidence,
            int ruleScore, Integer mlContribution, String mlStatus, String modelVersion,
            String mlFeatureVersion, String modelSha256,
            List<AdoptedMlEvidenceResponse> mlEvidence) {
        this(detectionResultId, detectionResultVersion, riskLevel, riskScore,
                analysisCompletedAt, ruleSetVersion, scoringPolicyVersion, ruleEvidence,
                ruleScore, mlContribution, mlStatus, modelVersion, mlFeatureVersion,
                modelSha256, mlEvidence, null);
    }
    public AdoptedDetectionResultItemResponse(UUID detectionResultId, int detectionResultVersion,
            String riskLevel, int riskScore, Instant analysisCompletedAt, String ruleSetVersion,
            String scoringPolicyVersion, List<AdoptedRuleEvidenceResponse> ruleEvidence) {
        this(detectionResultId, detectionResultVersion, riskLevel, riskScore,
                analysisCompletedAt, ruleSetVersion, scoringPolicyVersion, ruleEvidence,
                riskScore, null, "RULE_ONLY", null, null, null, List.of(), null);
    }
}
