package com.aifds.backend.transaction.dto;

import java.util.List;

public record TransactionListResponse(
        List<TransactionListItemResponse> content,
        PageMetadataResponse page,
        String traceId
) {
}
