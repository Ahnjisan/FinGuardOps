package com.aifds.backend.fraudcase.dto;

import java.util.List;

public record InvestigationNoteListResponse(
        List<InvestigationNoteListItemResponse> items,
        FraudCasePageMetadataResponse page,
        String traceId
) {
}
