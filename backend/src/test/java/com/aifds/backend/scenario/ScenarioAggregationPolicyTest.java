package com.aifds.backend.scenario;

import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository.Window;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioAggregationPolicyTest {
    private static final UUID PROOF = UUID.randomUUID();

    @Test
    void distributedOutboundRequiresThreeDistinctRecipientsAndCompleteHistory() {
        assertThat(ScenarioAggregationPolicy.distributedOutbound(facts(
                window(3, 3, "3000000", 0, PROOF), window(1, 1, "1", 0, PROOF))))
                .isTrue();
        assertThat(ScenarioAggregationPolicy.distributedOutbound(facts(
                window(5, 2, "9000000", 0, PROOF), window(1, 1, "1", 0, PROOF))))
                .isFalse();
        assertThat(ScenarioAggregationPolicy.distributedOutbound(facts(
                window(3, 3, "9000000", 1, PROOF), window(1, 1, "1", 0, PROOF))))
                .isFalse();
    }

    @Test
    void manyCustomerInboundRequiresDistinctCustomersAndProof() {
        assertThat(ScenarioAggregationPolicy.manyCustomerInbound(facts(
                window(1, 1, "1", 0, PROOF), window(3, 3, "5000000", 0, PROOF))))
                .isTrue();
        assertThat(ScenarioAggregationPolicy.manyCustomerInbound(facts(
                window(1, 1, "1", 0, PROOF), window(4, 2, "6000000", 0, PROOF))))
                .isFalse();
        assertThat(ScenarioAggregationPolicy.manyCustomerInbound(facts(
                window(1, 1, "1", 0, PROOF), window(3, 3, "5000000", 0, null))))
                .isFalse();
    }

    private ScenarioAggregationFacts facts(Window outbound, Window inbound) {
        return new ScenarioAggregationFacts("SCN004-005-contract-v1", outbound, inbound);
    }

    private Window window(long count, long distinct, String total, long unknown, UUID proof) {
        return new Window(count, distinct, new BigDecimal(total), unknown, proof);
    }
}
