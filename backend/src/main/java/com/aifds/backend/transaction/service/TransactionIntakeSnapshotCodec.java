package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransactionIntakeSnapshotCodec {

    private final ObjectMapper objectMapper;
    private final TransactionIntakeLegacySnapshotCodec legacyCodec;
    private final TransactionIntakeSnapshotEnvelopeCodec envelopeCodec;

    public TransactionIntakeSnapshotCodec(
            ObjectMapper objectMapper,
            TransactionIntakeLegacySnapshotCodec legacyCodec,
            TransactionIntakeSnapshotEnvelopeCodec envelopeCodec
    ) {
        this.objectMapper = objectMapper;
        this.legacyCodec = legacyCodec;
        this.envelopeCodec = envelopeCodec;
    }

    public JsonNode encode(
            TransactionIntakeSnapshot snapshot,
            int httpStatus,
            Instant finalizedAt
    ) {
        return envelopeCodec.encode(snapshot, httpStatus, finalizedAt);
    }

    public TransactionIntakeSnapshotReplay decode(String snapshotJson) {
        if (snapshotJson == null) {
            throw new InvalidTransactionIntakeSnapshotException();
        }

        final JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(snapshotJson);
        } catch (JsonProcessingException exception) {
            throw new InvalidTransactionIntakeSnapshotException(exception);
        }

        if (legacyCodec.matches(root)) {
            return legacyCodec.decode(root);
        }
        if (envelopeCodec.isCandidate(root)) {
            return envelopeCodec.decode(root);
        }
        throw new InvalidTransactionIntakeSnapshotException();
    }
}
