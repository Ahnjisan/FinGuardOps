package com.aifds.backend.fraudcase.dto;

import java.util.List;

public record FraudCaseListResponse(
        List<FraudCaseListItemResponse> content,
        FraudCasePageMetadataResponse page,
        String traceId
) {
}
