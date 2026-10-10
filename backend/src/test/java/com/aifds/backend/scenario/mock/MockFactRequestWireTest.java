package com.aifds.backend.scenario.mock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockFactRequestWireTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void absentOptionalFieldsAreNullUnderStrictCreatorSettings() throws Exception {
        UUID id = UUID.randomUUID();
        var body = """
                {"factId":"%s","factType":"TRANSFER_HISTORY_COMPLETE",
                 "externalCustomerRef":"customer","occurredAt":"2026-10-12T12:00:00Z",
                 "coverageFrom":"1970-01-01T00:00:00Z",
                 "coverageThrough":"2026-10-12T12:04:00Z",
                 "assertionState":"COMPLETE"}
                """.formatted(id);
        MockFactRequest request = mapper.readValue(body, MockFactRequest.class);
        assertThat(request.factId()).isEqualTo(id);
        assertThat(request.accountRef()).isNull();
        assertThat(request.behaviorEventId()).isNull();
        assertThat(request.previousLimit()).isNull();
    }

    @Test
    void unknownAndWrongTypedFieldsFailBeforeIntake() {
        String prefix = """
                {"factId":"%s","factType":"DEVICE_TRUST",
                 "externalCustomerRef":"customer","occurredAt":"2026-10-12T12:00:00Z",
                """.formatted(UUID.randomUUID());
        assertThatThrownBy(() -> mapper.readValue(prefix + "\"extra\":1}",
                MockFactRequest.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(prefix + "\"deviceRef\":123}",
                MockFactRequest.class)).isInstanceOf(Exception.class);
    }
}
