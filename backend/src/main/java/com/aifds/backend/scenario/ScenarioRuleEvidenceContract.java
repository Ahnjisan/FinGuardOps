package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV3;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/** Exact Evidence whitelist reconstructed from the trusted v4 Snapshot. */
public final class ScenarioRuleEvidenceContract {
    private ScenarioRuleEvidenceContract() { }

    public static JsonNode observation(int index, RuleAnalysisRequestV3 request) {
        ScenarioFacts f = request.scenarioFacts();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (index != 0 && index != 4) {
            node.put("sourceVersion", f.sourceVersion());
        }
        switch (index) {
            case 0 -> node.put("observedAmount", request.transaction().amount())
                    .put("amountThreshold", "10000000");
            case 1 -> node.put("deviceEventId", f.deviceEventId().toString())
                    .put("deviceFirstSeenAt", wire(f.deviceFirstSeenAt()))
                    .put("deviceStatus", f.deviceStatus())
                    .put("deviceCoverageFactId", required(f.deviceCoverageFactId()))
                    .put("deviceTrustFactId", required(f.deviceTrustFactId()));
            case 2 -> node.put("passwordEventId", f.passwordEventId().toString())
                    .put("passwordChangedAt", wire(f.passwordChangedAt()))
                    .put("limitEventId", f.limitEventId().toString())
                    .put("limitChangedAt", wire(f.limitChangedAt()))
                    .put("limitScope", f.limitScope())
                    .put("previousLimit", decimal(f.previousLimit()))
                    .put("newLimit", decimal(f.newLimit()))
                    .put("identityStatus", f.identityStatus())
                    .put("limitFactId", required(f.limitFactId()))
                    .put("identityFactId", optional(f.identityFactId()));
            case 3 -> node.put("beneficiaryEventId", f.beneficiaryEventId().toString())
                    .put("beneficiaryRegisteredAt", wire(f.beneficiaryRegisteredAt()))
                    .put("recipientStatus", f.recipientStatus())
                    .put("transferCoverageFactId", required(f.transferCoverageFactId()));
            case 4 -> node.put("providerCode", request.externalRisk().providerCode())
                    .put("providerAsOf", wire(request.externalRisk().providerAsOf()))
                    .put("lookedUpAt", wire(request.externalRisk().lookedUpAt()))
                    .put("freshnessSeconds", 86_400);
            case 5 -> node.put("baselineCount", f.baselineCount())
                    .put("baselineDays", f.baselineDays())
                    .put("baselineTotal", decimal(f.baselineTotal()))
                    .put("observedAmount", request.transaction().amount())
                    .put("transferCoverageFactId", required(f.transferCoverageFactId()));
            case 6 -> node.put("providerCode", request.externalRisk().providerCode())
                    .put("providerAsOf", wire(request.externalRisk().providerAsOf()))
                    .put("lookedUpAt", wire(request.externalRisk().lookedUpAt()))
                    .put("deviceMatched", true);
            case 7 -> node.put("appSecurityStatus", f.appSecurityStatus())
                    .put("signalAt", wire(f.appSecurityAt()))
                    .put("appSecurityFactId", required(f.appSecurityFactId()));
            case 8 -> node.put("limitEventId", f.limitEventId().toString())
                    .put("previousLimit", decimal(f.previousLimit()))
                    .put("newLimit", decimal(f.newLimit()))
                    .put("limitChangedAt", wire(f.limitChangedAt()))
                    .put("limitFactId", required(f.limitFactId()));
            case 9 -> node.put("networkRegionStatus", f.networkRegionStatus())
                    .put("signalAt", wire(f.networkRegionAt()))
                    .put("networkRegionFactId", required(f.networkRegionFactId()));
            default -> throw new IllegalArgumentException("Unsupported Rule v4 Evidence index");
        }
        if (index == 1 || index == 2 || index == 3 || index == 5
                || index == 7 || index == 8 || index == 9) {
            node.put("mockSourceCode", "LOCAL_FDS_MOCK");
            node.put("mockSourceVersion", "v1");
        }
        return node;
    }

    public static Instant occurredAt(int index, RuleAnalysisRequestV3 request) {
        ScenarioFacts f = request.scenarioFacts();
        return switch (index) {
            case 0, 5 -> request.evaluationCutoffAt();
            case 1 -> f.deviceFirstSeenAt();
            case 2, 8 -> f.limitChangedAt();
            case 3 -> f.beneficiaryRegisteredAt();
            case 4, 6 -> request.externalRisk().providerAsOf();
            case 7 -> f.appSecurityAt();
            case 9 -> f.networkRegionAt();
            default -> throw new IllegalArgumentException("Unsupported Rule v4 Evidence index");
        };
    }

    private static String decimal(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("Missing numeric Evidence source");
        try {
            return value.toBigIntegerExact().toString();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Non-integral KRW Evidence source", exception);
        }
    }

    private static String required(UUID value) {
        if (value == null) throw new IllegalArgumentException("Missing Mock Evidence source");
        return value.toString();
    }

    private static String optional(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String wire(Instant value) {
        if (value == null) throw new IllegalArgumentException("Missing time Evidence source");
        String whole = value.truncatedTo(ChronoUnit.SECONDS).toString();
        if (value.getNano() == 0) return whole;
        return whole.substring(0, whole.length() - 1)
                + String.format(Locale.ROOT, ".%06dZ", value.getNano() / 1_000);
    }
}
