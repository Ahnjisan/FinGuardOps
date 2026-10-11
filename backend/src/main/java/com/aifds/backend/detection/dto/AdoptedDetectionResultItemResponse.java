package com.aifds.backend.detection.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

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
        @JsonInclude(JsonInclude.Include.NON_NULL) Scn003EvidenceResponse scn003Evidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode scoreGroupSummary,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer mlRawContribution,
        @JsonInclude(JsonInclude.Include.NON_NULL) Scn001002EvidenceResponse scn001002Evidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode aggregationEvidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode fundFlowEvidence
) {
    public AdoptedDetectionResultItemResponse(UUID detectionResultId, int detectionResultVersion,
            String riskLevel, int riskScore, Instant analysisCompletedAt, String ruleSetVersion,
            String scoringPolicyVersion, List<AdoptedRuleEvidenceResponse> ruleEvidence,
            int ruleScore, Integer mlContribution, String mlStatus, String modelVersion,
            String mlFeatureVersion, String modelSha256,
            List<AdoptedMlEvidenceResponse> mlEvidence,
            Scn003EvidenceResponse scn003Evidence, JsonNode scoreGroupSummary,
            Integer mlRawContribution, Scn001002EvidenceResponse scn001002Evidence) {
        this(detectionResultId, detectionResultVersion, riskLevel, riskScore,
                analysisCompletedAt, ruleSetVersion, scoringPolicyVersion, ruleEvidence,
                ruleScore, mlContribution, mlStatus, modelVersion, mlFeatureVersion,
                modelSha256, mlEvidence, scn003Evidence, scoreGroupSummary,
                mlRawContribution, scn001002Evidence, null, null);
    }
    public AdoptedDetectionResultItemResponse(UUID detectionResultId, int detectionResultVersion,
            String riskLevel, int riskScore, Instant analysisCompletedAt, String ruleSetVersion,
            String scoringPolicyVersion, List<AdoptedRuleEvidenceResponse> ruleEvidence,
            int ruleScore, Integer mlContribution, String mlStatus, String modelVersion,
            String mlFeatureVersion, String modelSha256,
            List<AdoptedMlEvidenceResponse> mlEvidence,
            Scn003EvidenceResponse scn003Evidence, JsonNode scoreGroupSummary,
            Integer mlRawContribution, Scn001002EvidenceResponse scn001002Evidence,
            JsonNode aggregationEvidence) {
        this(detectionResultId, detectionResultVersion, riskLevel, riskScore,
                analysisCompletedAt, ruleSetVersion, scoringPolicyVersion, ruleEvidence,
                ruleScore, mlContribution, mlStatus, modelVersion, mlFeatureVersion,
                modelSha256, mlEvidence, scn003Evidence, scoreGroupSummary,
                mlRawContribution, scn001002Evidence, aggregationEvidence, null);
    }
    public AdoptedDetectionResultItemResponse(UUID detectionResultId, int detectionResultVersion,
            String riskLevel, int riskScore, Instant analysisCompletedAt, String ruleSetVersion,
            String scoringPolicyVersion, List<AdoptedRuleEvidenceResponse> ruleEvidence,
            int ruleScore, Integer mlContribution, String mlStatus, String modelVersion,
            String mlFeatureVersion, String modelSha256,
            List<AdoptedMlEvidenceResponse> mlEvidence) {
        this(detectionResultId, detectionResultVersion, riskLevel, riskScore,
                analysisCompletedAt, ruleSetVersion, scoringPolicyVersion, ruleEvidence,
                ruleScore, mlContribution, mlStatus, modelVersion, mlFeatureVersion,
                modelSha256, mlEvidence, null, null, null, null, null, null);
    }
    public AdoptedDetectionResultItemResponse(UUID detectionResultId, int detectionResultVersion,
            String riskLevel, int riskScore, Instant analysisCompletedAt, String ruleSetVersion,
            String scoringPolicyVersion, List<AdoptedRuleEvidenceResponse> ruleEvidence) {
        this(detectionResultId, detectionResultVersion, riskLevel, riskScore,
                analysisCompletedAt, ruleSetVersion, scoringPolicyVersion, ruleEvidence,
                riskScore, null, "RULE_ONLY", null, null, null, List.of(), null,
                null, null, null, null, null);
    }
}
