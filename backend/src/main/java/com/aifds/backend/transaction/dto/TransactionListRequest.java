package com.aifds.backend.transaction.dto;

public record TransactionListRequest(
        String occurredAtFrom,
        String occurredAtTo,
        String transactionType,
        String processingStatus,
        String externalCustomerRef,
        String accountRef,
        String page,
        String size,
        String sort,
        int transactionTypeValueCount,
        int processingStatusValueCount,
        int sortValueCount
) {

    public TransactionListRequest(
            String occurredAtFrom,
            String occurredAtTo,
            String transactionType,
            String processingStatus,
            String externalCustomerRef,
            String accountRef,
            String page,
            String size,
            String sort
    ) {
        this(
                occurredAtFrom,
                occurredAtTo,
                transactionType,
                processingStatus,
                externalCustomerRef,
                accountRef,
                page,
                size,
                sort,
                transactionType == null ? 0 : 1,
                processingStatus == null ? 0 : 1,
                sort == null ? 0 : 1
        );
    }
}
