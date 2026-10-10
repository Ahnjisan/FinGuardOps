package com.aifds.backend.detection.ml;

import com.aifds.backend.detection.entity.RiskLevel;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlDetectionPolicyTest {
    @Test
    void delayedTransactionKeepsModelSelectedAtItsCutoff() {
        Instant boundary = MlDetectionPolicy.MODEL_V2_EFFECTIVE_FROM;
        assertThat(MlDetectionPolicy.modelForCutoff(boundary.minusNanos(1)))
                .isEqualTo(MlDetectionPolicy.MODEL_V1_VERSION);
        assertThat(MlDetectionPolicy.modelForCutoff(boundary))
                .isEqualTo(MlDetectionPolicy.MODEL_VERSION);
        assertThat(MlDetectionPolicy.shaForModel(MlDetectionPolicy.MODEL_V1_VERSION))
                .isEqualTo(MlDetectionPolicy.MODEL_V1_SHA256);
        assertThat(MlDetectionPolicy.shaForModel(MlDetectionPolicy.MODEL_VERSION))
                .isEqualTo(MlDetectionPolicy.MODEL_SHA256);
    }

    @Test
    void contributionBoundariesAndRiskTransitions() {
        assertThat(MlDetectionPolicy.contribution(0)).isZero();
        assertThat(MlDetectionPolicy.contribution(5000)).isZero();
        assertThat(MlDetectionPolicy.contribution(5001)).isZero();
        assertThat(MlDetectionPolicy.contribution(5063)).isEqualTo(1);
        assertThat(MlDetectionPolicy.contribution(10000)).isEqualTo(40);
        assertThat(MlDetectionPolicy.finalScore(79, 1)).isEqualTo(80);
        assertThat(MlDetectionPolicy.finalScore(80, 40)).isEqualTo(100);
        assertThat(MlDetectionPolicy.riskLevel(19)).isEqualTo(RiskLevel.LOW);
        assertThat(MlDetectionPolicy.riskLevel(20)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(MlDetectionPolicy.riskLevel(49)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(MlDetectionPolicy.riskLevel(50)).isEqualTo(RiskLevel.HIGH);
        assertThat(MlDetectionPolicy.riskLevel(79)).isEqualTo(RiskLevel.HIGH);
        assertThat(MlDetectionPolicy.riskLevel(80)).isEqualTo(RiskLevel.CRITICAL);
        assertThatThrownBy(() -> MlDetectionPolicy.contribution(10001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
