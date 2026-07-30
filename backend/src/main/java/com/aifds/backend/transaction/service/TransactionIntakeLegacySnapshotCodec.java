package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class TransactionIntakeLegacySnapshotCodec {

    static final int LEGACY_REPLAY_HTTP_STATUS = 200;

    private final TransactionIntakeSnapshotBodyCodec bodyCodec;

    TransactionIntakeLegacySnapshotCodec(
            TransactionIntakeSnapshotBodyCodec bodyCodec
    ) {
        this.bodyCodec = bodyCodec;
    }

    boolean matches(JsonNode root) {
        return bodyCodec.hasExactFields(root);
    }

    TransactionIntakeSnapshotReplay decode(JsonNode root) {
        if (!matches(root)) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return new TransactionIntakeSnapshotReplay(
                bodyCodec.decode(root),
                LEGACY_REPLAY_HTTP_STATUS
        );
    }
}
