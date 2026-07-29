package com.aifds.backend.transaction.query;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.Objects;

public record TransactionQueryCriteria(
        Instant occurredAtFrom,
        Instant occurredAtTo,
        TransactionType transactionType,
        TransactionProcessingStatus processingStatus,
        String externalCustomerRef,
        String accountRef,
        int page,
        int size,
        Sort.Direction sortDirection
) {

    public TransactionQueryCriteria {
        Objects.requireNonNull(
                sortDirection,
                "sortDirection must not be null"
        );
    }
}
