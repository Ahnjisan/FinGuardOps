package com.aifds.backend.transaction.service;

import java.util.Objects;

public record TransactionIntakeSnapshotReplay(
        TransactionIntakeSnapshot snapshot,
        int httpStatus
) {

    public TransactionIntakeSnapshotReplay {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (httpStatus != 200 && httpStatus != 201) {
            throw new IllegalArgumentException(
                    "Transaction intake replay HTTP status must be 200 or 201"
            );
        }
    }
}
