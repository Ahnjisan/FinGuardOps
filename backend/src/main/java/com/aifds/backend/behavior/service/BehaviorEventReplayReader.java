package com.aifds.backend.behavior.service;

import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class BehaviorEventReplayReader {

    private final BehaviorEventRepository behaviorEventRepository;
    private final BehaviorEventSnapshotFactory snapshotFactory;

    public BehaviorEventReplayReader(
            BehaviorEventRepository behaviorEventRepository,
            BehaviorEventSnapshotFactory snapshotFactory
    ) {
        this.behaviorEventRepository = behaviorEventRepository;
        this.snapshotFactory = snapshotFactory;
    }

    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW
    )
    public Optional<BehaviorEventIntakeSnapshot> findByEventId(UUID eventId) {
        return behaviorEventRepository.findByEventId(eventId)
                .map(snapshotFactory::from);
    }
}
