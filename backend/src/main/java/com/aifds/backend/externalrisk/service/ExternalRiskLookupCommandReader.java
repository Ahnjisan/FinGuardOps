package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class ExternalRiskLookupCommandReader {

    private final FinancialTransactionRepository transactionRepository;

    public ExternalRiskLookupCommandReader(
            FinancialTransactionRepository transactionRepository
    ) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository,
                "transactionRepository must not be null"
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ExternalRiskLookupCommand read(
            UUID transactionId,
            String traceId
    ) {
        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(transactionId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Transaction was not found"
                        )
                );
        if (transaction.getProcessingStatus()
                != TransactionProcessingStatus.RECEIVED) {
            throw new IllegalStateException(
                    "Transaction processing status must be RECEIVED"
            );
        }

        return new ExternalRiskLookupCommand(
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                transaction.getOccurredAt(),
                transaction.getExternalCustomerRef(),
                transaction.getSenderAccountRef(),
                transaction.getRecipientAccountRef(),
                transaction.getDeviceRef(),
                traceId
        );
    }
}
