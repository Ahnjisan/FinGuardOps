package com.aifds.backend.behavior.service;

import com.aifds.backend.behavior.command.ValidatedBehaviorEventCommand;
import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BehaviorEventIntakeWriter {

    private final BehaviorEventRepository behaviorEventRepository;
    private final BehaviorEventSnapshotFactory snapshotFactory;
    private final EntityManager entityManager;

    public BehaviorEventIntakeWriter(
            BehaviorEventRepository behaviorEventRepository,
            BehaviorEventSnapshotFactory snapshotFactory,
            EntityManager entityManager
    ) {
        this.behaviorEventRepository = behaviorEventRepository;
        this.snapshotFactory = snapshotFactory;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BehaviorEventIntakeSnapshot create(
            ValidatedBehaviorEventCommand command,
            String requestFingerprint,
            Long financialTransactionId
    ) {
        FinancialTransaction transaction = financialTransactionId == null
                ? null
                : entityManager.getReference(
                        FinancialTransaction.class,
                        financialTransactionId
                );
        BehaviorEvent event = new BehaviorEvent(
                command.eventId(),
                command.eventType(),
                command.occurredAt(),
                command.externalCustomerRef(),
                command.accountRef(),
                command.deviceRef(),
                command.beneficiaryRef(),
                transaction,
                requestFingerprint
        );
        BehaviorEvent saved = behaviorEventRepository.saveAndFlush(event);
        entityManager.refresh(saved);
        return snapshotFactory.from(saved);
    }
}
