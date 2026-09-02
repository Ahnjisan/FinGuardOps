package com.aifds.backend.fraudcase.dto;

import java.util.List;
import java.util.UUID;

public record FraudCaseAuditLogListResponse(
        UUID caseId,
        List<FraudCaseAuditLogListItemResponse> content,
        FraudCasePageMetadataResponse page,
        String traceId
) {

    public FraudCaseAuditLogListResponse {
        content = List.copyOf(content);
    }

    @Override
    public List<FraudCaseAuditLogListItemResponse> content() {
        return content;
    }
}
