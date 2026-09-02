package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.fraudcase.validation.InvestigationNoteValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationNoteCreateRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsOnlyExactStringContentAndIntegerVersionWithoutNormalizingContent()
            throws Exception {
        String json = "{\"content\":\"  😀\\r\\n  \",\"expectedVersion\":6}";

        InvestigationNoteCreateRequest request = objectMapper.readValue(
                json, InvestigationNoteCreateRequest.class
        );

        assertThat(request.content()).isEqualTo("  😀\r\n  ");
        assertThat(request.expectedVersion()).isEqualTo(6L);
    }

    @Test
    void rejectsWrongRootUnknownActorFieldsDuplicatesTrailingAndVersionCoercion() {
        for (String json : new String[]{
                "null", "[]", "true", "1", "\"text\"",
                "{\"content\":\"x\",\"expectedVersion\":6,\"authorRef\":\"x\"}",
                "{\"content\":\"x\",\"expectedVersion\":6,\"actorType\":\"USER\"}",
                "{\"content\":\"x\",\"expectedVersion\":6,\"actorId\":\"x\"}",
                "{\"content\":\"x\",\"content\":\"y\",\"expectedVersion\":6}",
                "{\"content\":1,\"expectedVersion\":6}",
                "{\"content\":\"x\",\"expectedVersion\":\"6\"}",
                "{\"content\":\"x\",\"expectedVersion\":6.0}",
                "{\"content\":\"x\",\"expectedVersion\":false}",
                "{\"content\":\"x\",\"expectedVersion\":9223372036854775808}",
                "{\"content\":\"x\",\"expectedVersion\":6} {}"
        }) {
            assertThatThrownBy(() -> objectMapper.readValue(
                    json, InvestigationNoteCreateRequest.class
            )).hasRootCauseInstanceOf(InvestigationNoteValidationException.class);
        }
    }
}
