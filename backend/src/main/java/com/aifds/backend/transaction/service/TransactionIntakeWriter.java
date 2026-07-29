package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionIntakeWriter {

    private final FinancialTransactionRepository financialTransactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final EntityManager entityManager;

    public TransactionIntakeWriter(
            FinancialTransactionRepository financialTransactionRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            EntityManager entityManager
    ) {
        this.financialTransactionRepository = financialTransactionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public PersistedTransactionIntake saveAndLink(
            long idempotencyRecordId,
            ValidatedTransactionCommand command
    ) {
        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdForUpdate(idempotencyRecordId)
                .orElseThrow(
                        () -> new IdempotencyRecordNotFoundException(
                                idempotencyRecordId
                        )
                );
        if (record.getProcessingStatus()
                != IdempotencyProcessingStatus.IN_PROGRESS) {
            throw new IdempotencyStateTransitionNotAllowedException(
                    record.getProcessingStatus(),
                    IdempotencyProcessingStatus.IN_PROGRESS
            );
        }

        FinancialTransaction transaction = new FinancialTransaction(
                command.transactionId(),
                command.transactionType(),
                command.amount(),
                command.currencyCode(),
                command.occurredAt(),
                command.externalCustomerRef(),
                command.senderAccountRef(),
                command.recipientAccountRef(),
                command.channel(),
                command.deviceRef()
        );
        FinancialTransaction saved =
                financialTransactionRepository.saveAndFlush(transaction);
        entityManager.refresh(saved);

        record.linkTransaction(saved);
        idempotencyRecordRepository.saveAndFlush(record);

        return new PersistedTransactionIntake(
                saved.getTransactionId(),
                saved.getProcessingStatus(),
                saved.getCreatedAt()
        );
    }
}
