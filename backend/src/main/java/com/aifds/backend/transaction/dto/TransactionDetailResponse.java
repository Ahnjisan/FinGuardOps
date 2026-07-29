package com.aifds.backend.transaction.dto;

public record TransactionDetailResponse(
        TransactionDetailItemResponse transaction,
        String traceId
) {
}
