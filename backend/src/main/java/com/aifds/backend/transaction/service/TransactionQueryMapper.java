package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.dto.TransactionDetailItemResponse;
import com.aifds.backend.transaction.dto.TransactionListItemResponse;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class TransactionQueryMapper {

    public TransactionListItemResponse toListItem(
            FinancialTransaction transaction
    ) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return new TransactionListItemResponse(
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                amountToString(transaction.getAmount()),
                transaction.getCurrencyCode(),
                transaction.getOccurredAt(),
                transaction.getExternalCustomerRef(),
                transaction.getSenderAccountRef(),
                transaction.getRecipientAccountRef(),
                transaction.getProcessingStatus(),
                transaction.getCreatedAt()
        );
    }

    public TransactionDetailItemResponse toDetailItem(
            FinancialTransaction transaction
    ) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return new TransactionDetailItemResponse(
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                amountToString(transaction.getAmount()),
                transaction.getCurrencyCode(),
                transaction.getOccurredAt(),
                transaction.getExternalCustomerRef(),
                transaction.getSenderAccountRef(),
                transaction.getRecipientAccountRef(),
                transaction.getChannel(),
                transaction.getDeviceRef(),
                transaction.getProcessingStatus(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }

    String amountToString(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.stripTrailingZeros().toPlainString();
    }
}
