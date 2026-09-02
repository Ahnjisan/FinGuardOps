package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCasePageMetadataResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListItemResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.InvestigationNote;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InvestigationNoteMapper {

    public InvestigationNoteCreateResponse toCreateResponse(
            InvestigationNote note,
            FraudCase fraudCase,
            String traceId
    ) {
        return new InvestigationNoteCreateResponse(
                note.getNoteId(), fraudCase.getCaseId(), note.getAuthorType(),
                note.getAuthorRef(), note.getContent(), note.getCreatedAt(),
                fraudCase.getConcurrencyVersion(), traceId
        );
    }

    public InvestigationNoteListResponse toListResponse(
            Page<InvestigationNote> page,
            UUID caseId,
            String traceId
    ) {
        return new InvestigationNoteListResponse(
                page.getContent().stream().map(note -> new InvestigationNoteListItemResponse(
                        note.getNoteId(), caseId, note.getAuthorType(), note.getAuthorRef(),
                        note.getContent(), note.getCreatedAt()
                )).toList(),
                new FraudCasePageMetadataResponse(
                        page.getNumber(), page.getSize(), page.getTotalElements(),
                        page.getTotalPages(), page.isFirst(), page.isLast()
                ),
                traceId
        );
    }
}
