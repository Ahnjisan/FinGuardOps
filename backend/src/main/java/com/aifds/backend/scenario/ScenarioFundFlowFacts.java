package com.aifds.backend.scenario;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Proven source-to-use facts only; raw customer and account references stay server-side. */
public record ScenarioFundFlowFacts(String sourceVersion, Flow atm, Flow loan) {
    public ScenarioFundFlowFacts {
        if (!"SCN006-007-contract-v1".equals(sourceVersion)
                || atm == null || loan == null) {
            throw new IllegalArgumentException("Invalid fund flow facts");
        }
    }

    public record Flow(String status, String reasonCode, UUID sourceTransactionId,
            UUID currentLinkId, UUID historyCoverageFactId, UUID beneficiaryEventId,
            Instant sourceOccurredAt, Instant sourceApprovedAt,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal sourceAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal linkedAmount,
            int ratioBasisPoints, List<UUID> linkedPriorTransactionIds) {
        public Flow {
            if (!("AVAILABLE".equals(status) || "UNKNOWN".equals(status))
                    || reasonCode == null || sourceAmount == null || linkedAmount == null
                    || ratioBasisPoints < 0 || ratioBasisPoints > 10000
                    || linkedPriorTransactionIds == null) {
                throw new IllegalArgumentException("Invalid fund flow");
            }
            linkedPriorTransactionIds = List.copyOf(linkedPriorTransactionIds);
        }

        public boolean hit() {
            return "AVAILABLE".equals(status) && ratioBasisPoints >= 8000;
        }

        public static Flow unknown(String reason) {
            return new Flow("UNKNOWN", reason, null, null, null, null,
                    null, null, BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
        }
    }
}
