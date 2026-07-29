package com.aifds.backend.behavior.service;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BehaviorEventSnapshotFactory {

    public BehaviorEventIntakeSnapshot from(BehaviorEvent event) {
        UUID transactionId = event.getFinancialTransaction() == null
                ? null
                : event.getFinancialTransaction().getTransactionId();
        return new BehaviorEventIntakeSnapshot(
                event.getEventId(),
                event.getEventType(),
                transactionId,
                event.getOccurredAt(),
                event.getCreatedAt(),
                event.getRequestFingerprint()
        );
    }
}
