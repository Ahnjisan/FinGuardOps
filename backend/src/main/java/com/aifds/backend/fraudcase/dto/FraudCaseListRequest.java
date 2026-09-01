package com.aifds.backend.fraudcase.dto;

public record FraudCaseListRequest(
        String caseStatus,
        String finalDisposition,
        String assigneeRef,
        String createdAtFrom,
        String createdAtTo,
        String lastChangedAtFrom,
        String lastChangedAtTo,
        String transactionId,
        String page,
        String size,
        String sort,
        int caseStatusValueCount,
        int finalDispositionValueCount,
        int assigneeRefValueCount,
        int createdAtFromValueCount,
        int createdAtToValueCount,
        int lastChangedAtFromValueCount,
        int lastChangedAtToValueCount,
        int transactionIdValueCount,
        int pageValueCount,
        int sizeValueCount,
        int sortValueCount
) {

    public FraudCaseListRequest(
            String caseStatus,
            String finalDisposition,
            String assigneeRef,
            String createdAtFrom,
            String createdAtTo,
            String lastChangedAtFrom,
            String lastChangedAtTo,
            String transactionId,
            String page,
            String size,
            String sort
    ) {
        this(
                caseStatus,
                finalDisposition,
                assigneeRef,
                createdAtFrom,
                createdAtTo,
                lastChangedAtFrom,
                lastChangedAtTo,
                transactionId,
                page,
                size,
                sort,
                count(caseStatus),
                count(finalDisposition),
                count(assigneeRef),
                count(createdAtFrom),
                count(createdAtTo),
                count(lastChangedAtFrom),
                count(lastChangedAtTo),
                count(transactionId),
                count(page),
                count(size),
                count(sort)
        );
    }

    private static int count(String value) {
        return value == null ? 0 : 1;
    }
}
