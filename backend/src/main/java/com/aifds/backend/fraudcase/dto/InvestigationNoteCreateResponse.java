package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.fraudcase.entity.InvestigationNoteAuthorType;

import java.time.Instant;
import java.util.UUID;

public record InvestigationNoteCreateResponse(
        UUID noteId,
        UUID caseId,
        InvestigationNoteAuthorType authorType,
        String authorRef,
        String content,
        Instant createdAt,
        long concurrencyVersion,
        String traceId
) {
}
