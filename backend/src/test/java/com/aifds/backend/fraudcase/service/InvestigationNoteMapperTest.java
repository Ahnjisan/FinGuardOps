package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.InvestigationNote;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestigationNoteMapperTest {

    private final InvestigationNoteMapper mapper = new InvestigationNoteMapper();

    @Test
    void mapsCreateAndPageWithoutExposingInternalIdentity() {
        UUID caseId = UUID.randomUUID();
        InvestigationNote note = InvestigationNote.systemAuthored(
                UUID.randomUUID(), 91L, "  memo\n", Instant.parse("2026-09-02T00:00:00Z")
        );
        FraudCase fraudCase = mock(FraudCase.class);
        when(fraudCase.getCaseId()).thenReturn(caseId);
        when(fraudCase.getConcurrencyVersion()).thenReturn(7L);

        var created = mapper.toCreateResponse(note, fraudCase, "trace_note_001");
        var listed = mapper.toListResponse(
                new PageImpl<>(List.of(note), PageRequest.of(0, 20), 1),
                caseId, "trace_note_001"
        );

        assertThat(created.content()).isEqualTo("  memo\n");
        assertThat(created.concurrencyVersion()).isEqualTo(7);
        assertThat(listed.items()).singleElement().satisfies(item -> {
            assertThat(item.caseId()).isEqualTo(caseId);
            assertThat(item.content()).isEqualTo("  memo\n");
        });
        assertThat(listed.page().totalElements()).isEqualTo(1);
        assertThat(listed.traceId()).isEqualTo("trace_note_001");
    }
}
