package com.aifds.backend.scenario;

import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository.Window;

/** Only aggregate observations, without raw customer or account references. */
public record ScenarioAggregationFacts(String sourceVersion, Window outbound,
                                       Window inbound) {
    public ScenarioAggregationFacts {
        if (!"SCN004-005-contract-v1".equals(sourceVersion)
                || outbound == null || inbound == null) {
            throw new IllegalArgumentException("Invalid aggregation facts");
        }
    }
}
