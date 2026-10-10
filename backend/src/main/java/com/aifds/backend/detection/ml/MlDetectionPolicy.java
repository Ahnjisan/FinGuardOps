package com.aifds.backend.detection.ml;

import com.aifds.backend.detection.entity.RiskLevel;
import java.time.Instant;

/** Synthetic-only local policy. This is not a production fraud threshold. */
public final class MlDetectionPolicy {
    public static final String POLICY_VERSION = "rule-ml-policy-v1";
    public static final String SCN003_POLICY_VERSION = "rule-ml-policy-v2";
    public static final String FEATURE_VERSION = "fraud-feature-v1";
    public static final String MODEL_V1_VERSION = "fraud-logistic-v1";
    public static final String MODEL_V1_SHA256 =
            "93a91797f6a652a08871191e87385ced6edd1a560abd6ee9b2782411bb19c286";
    public static final String MODEL_VERSION = "fraud-logistic-v2";
    public static final String MODEL_SHA256 =
            "42344d398008babdd6a0404c750b24f1f27f1260aeac65851195d81500a876af";
    public static final Instant MODEL_V2_EFFECTIVE_FROM =
            Instant.parse("2026-10-10T00:40:00Z");

    private MlDetectionPolicy() { }

    public static String modelForCutoff(Instant cutoff) {
        return cutoff.isBefore(MODEL_V2_EFFECTIVE_FROM) ? MODEL_V1_VERSION : MODEL_VERSION;
    }

    public static String shaForModel(String modelVersion) {
        if (MODEL_V1_VERSION.equals(modelVersion)) return MODEL_V1_SHA256;
        if (MODEL_VERSION.equals(modelVersion)) return MODEL_SHA256;
        throw new IllegalArgumentException("Unknown pinned model version");
    }

    public static int contribution(int probabilityBasisPoints) {
        if (probabilityBasisPoints < 0 || probabilityBasisPoints > 10000) {
            throw new IllegalArgumentException("ML probability outside range");
        }
        return probabilityBasisPoints <= 5000 ? 0
                : ((probabilityBasisPoints - 5000) * 40 + 2500) / 5000;
    }

    public static int finalScore(int ruleScore, int contribution) {
        if (ruleScore < 0 || ruleScore > 100 || contribution < 0 || contribution > 40) {
            throw new IllegalArgumentException("Rule or ML score outside range");
        }
        return Math.min(100, ruleScore + contribution);
    }

    public static RiskLevel riskLevel(int score) {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score outside range");
        if (score < 20) return RiskLevel.LOW;
        if (score < 50) return RiskLevel.MEDIUM;
        if (score < 80) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }
}
