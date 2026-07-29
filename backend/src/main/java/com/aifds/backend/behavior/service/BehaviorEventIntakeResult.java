package com.aifds.backend.behavior.service;

import java.util.Objects;

public sealed interface BehaviorEventIntakeResult {

    record Created(BehaviorEventIntakeSnapshot snapshot)
            implements BehaviorEventIntakeResult {

        public Created {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    record Replay(BehaviorEventIntakeSnapshot snapshot)
            implements BehaviorEventIntakeResult {

        public Replay {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }
}
