package com.aifds.backend.scenario;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRuleResponseValidatorTest {
    @Test
    void comparesIntegralEvidenceNumbersByValueWithoutRelaxingFields() {
        var expected = JsonNodeFactory.instance.objectNode()
                .put("baselineCount", 10L).put("baselineDays", 3L)
                .put("baselineTotal", "1000000");
        var parsed = JsonNodeFactory.instance.objectNode()
                .put("baselineCount", 10).put("baselineDays", 3)
                .put("baselineTotal", "1000000");
        assertThat(ScenarioRuleResponseValidator.sameObservation(expected, parsed)).isTrue();
        parsed.put("baselineTotal", "1000000.0");
        assertThat(ScenarioRuleResponseValidator.sameObservation(expected, parsed)).isFalse();
        parsed.put("baselineTotal", "1000000");
        parsed.put("unapprovedField", "x");
        assertThat(ScenarioRuleResponseValidator.sameObservation(expected, parsed)).isFalse();
    }
}
