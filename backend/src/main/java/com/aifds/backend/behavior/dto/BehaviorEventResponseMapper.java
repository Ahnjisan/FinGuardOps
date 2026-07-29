package com.aifds.backend.behavior.dto;

import com.aifds.backend.behavior.service.BehaviorEventIntakeSnapshot;
import org.springframework.stereotype.Component;

@Component
public class BehaviorEventResponseMapper {

    public BehaviorEventCreateResponse toResponse(
            BehaviorEventIntakeSnapshot snapshot,
            String traceId
    ) {
        return new BehaviorEventCreateResponse(
                snapshot.eventId(),
                snapshot.eventType(),
                snapshot.transactionId(),
                snapshot.occurredAt(),
                snapshot.createdAt(),
                traceId
        );
    }
}
