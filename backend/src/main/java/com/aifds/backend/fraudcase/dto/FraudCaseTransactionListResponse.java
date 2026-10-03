package com.aifds.backend.fraudcase.dto;

import java.util.List;
import java.util.UUID;

public record FraudCaseTransactionListResponse(
        UUID caseId,
        List<FraudCaseTransactionListItemResponse> content,
        FraudCasePageMetadataResponse page,
        String traceId
) {
}
