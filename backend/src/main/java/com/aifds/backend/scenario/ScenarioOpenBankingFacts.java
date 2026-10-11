package com.aifds.backend.scenario;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Cutoff-fixed, explicitly linked local Mock concentration and resend stages. */
public record ScenarioOpenBankingFacts(String sourceVersion, String status, String reasonCode,
        UUID historyCoverageFactId, Instant concentrationStartedAt,
        Instant concentrationCompletedAt,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal concentrationAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal resendAmount,
        int ratioBasisPoints, List<Stage> stages) {
    public ScenarioOpenBankingFacts {
        if (!"SCN008-contract-v1".equals(sourceVersion)
                || !("AVAILABLE".equals(status) || "UNKNOWN".equals(status))
                || reasonCode == null || concentrationAmount == null || resendAmount == null
                || ratioBasisPoints < 0 || ratioBasisPoints > 10000 || stages == null) {
            throw new IllegalArgumentException("Invalid open banking facts");
        }
        stages = List.copyOf(stages);
    }

    public boolean hit() {
        return "AVAILABLE".equals(status) && stages.size() >= 2
                && concentrationAmount.compareTo(new BigDecimal("5000000")) >= 0
                && ratioBasisPoints >= 8000;
    }

    public static ScenarioOpenBankingFacts unknown(String reason) {
        return new ScenarioOpenBankingFacts("SCN008-contract-v1", "UNKNOWN", reason,
                null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
    }

    public record Stage(UUID sourceTransactionId, UUID ownershipAssertionId, UUID linkId,
            Instant occurredAt, Instant approvedAt,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount) { }
}
