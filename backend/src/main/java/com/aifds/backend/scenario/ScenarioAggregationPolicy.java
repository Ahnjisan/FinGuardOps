package com.aifds.backend.scenario;

import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository.Window;

import java.math.BigDecimal;
import java.util.Objects;

/** Local synthetic thresholds; window observations alone never establish fraud. */
public final class ScenarioAggregationPolicy {
    private static final BigDecimal OUTBOUND_TOTAL = new BigDecimal("3000000");
    private static final BigDecimal INBOUND_TOTAL = new BigDecimal("5000000");

    private ScenarioAggregationPolicy() { }

    public static boolean distributedOutbound(ScenarioAggregationFacts facts) {
        Window window = Objects.requireNonNull(facts).outbound();
        return window.available() && window.transferCount() >= 3
                && window.distinctCount() >= 3
                && window.totalAmount().compareTo(OUTBOUND_TOTAL) >= 0;
    }

    public static boolean manyCustomerInbound(ScenarioAggregationFacts facts) {
        Window window = Objects.requireNonNull(facts).inbound();
        return window.available() && window.transferCount() >= 3
                && window.distinctCount() >= 3
                && window.totalAmount().compareTo(INBOUND_TOTAL) >= 0;
    }
}
