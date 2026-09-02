package com.aifds.backend.fraudcase.entity;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationNoteTest {

    @Test
    void createsOnlyApprovedSystemAuthoredImmutableNoteWithoutChangingContent() {
        String content = "  조사 메모\r\n<script>alert(1)</script>  ";
        Instant createdAt = Instant.parse("2026-09-02T00:00:00.123456Z");

        InvestigationNote note = InvestigationNote.systemAuthored(
                UUID.randomUUID(), 1L, content, createdAt
        );

        assertThat(note.getNoteId().version()).isEqualTo(4);
        assertThat(note.getAuthorType()).isEqualTo(InvestigationNoteAuthorType.SYSTEM);
        assertThat(note.getAuthorRef()).isEqualTo("finguardops-backend");
        assertThat(note.getContent()).isSameAs(content);
        assertThat(note.getCreatedAt()).isEqualTo(createdAt);
        assertThat(InvestigationNote.class).hasAnnotation(Immutable.class);
    }

    @Test
    void rejectsInvalidIdentityCaseAndTimestampBoundaries() {
        assertThatThrownBy(() -> InvestigationNote.systemAuthored(
                UUID.fromString("10000000-0000-1000-8000-000000000001"),
                1L, "memo", Instant.parse("2026-09-02T00:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestigationNote.systemAuthored(
                UUID.randomUUID(), 0L, "memo", Instant.parse("2026-09-02T00:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestigationNote.systemAuthored(
                UUID.randomUUID(), 1L, "memo", Instant.parse("2026-09-02T00:00:00.000000001Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycleCallbacksRejectUpdateAndDelete() throws Exception {
        InvestigationNote note = InvestigationNote.systemAuthored(
                UUID.randomUUID(), 1L, "memo", Instant.parse("2026-09-02T00:00:00Z")
        );
        for (String callback : new String[]{"rejectUpdate", "rejectRemove"}) {
            Method method = InvestigationNote.class.getDeclaredMethod(callback);
            method.setAccessible(true);
            assertThatThrownBy(() -> method.invoke(note))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
}
