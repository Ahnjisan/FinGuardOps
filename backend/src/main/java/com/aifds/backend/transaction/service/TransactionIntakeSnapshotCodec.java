package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransactionIntakeSnapshotCodec {

    private final ObjectMapper objectMapper;
    private final TransactionIntakeLegacySnapshotCodec legacyCodec;
    private final TransactionIntakeSnapshotEnvelopeCodec envelopeCodec;
    private final TransactionIntakeSnapshotEnvelopeV2Codec envelopeV2Codec;

    @Autowired
    public TransactionIntakeSnapshotCodec(
            ObjectMapper objectMapper,
            TransactionIntakeLegacySnapshotCodec legacyCodec,
            TransactionIntakeSnapshotEnvelopeCodec envelopeCodec,
            TransactionIntakeSnapshotEnvelopeV2Codec envelopeV2Codec
    ) {
        this.objectMapper = objectMapper;
        this.legacyCodec = legacyCodec;
        this.envelopeCodec = envelopeCodec;
        this.envelopeV2Codec = envelopeV2Codec;
    }

    public TransactionIntakeSnapshotCodec(
            ObjectMapper objectMapper,
            TransactionIntakeLegacySnapshotCodec legacyCodec,
            TransactionIntakeSnapshotEnvelopeCodec envelopeCodec
    ) {
        this(
                objectMapper,
                legacyCodec,
                envelopeCodec,
                new TransactionIntakeSnapshotEnvelopeV2Codec(
                        objectMapper,
                        new TransactionFinalResponseSnapshotBodyCodec(
                                objectMapper
                        )
                )
        );
    }

    public JsonNode encode(
            TransactionIntakeSnapshot snapshot,
            int httpStatus,
            Instant finalizedAt
    ) {
        return envelopeCodec.encode(snapshot, httpStatus, finalizedAt);
    }

    public JsonNode encodeV2(
            TransactionFinalResponseSnapshot snapshot,
            int httpStatus,
            Instant finalizedAt
    ) {
        return envelopeV2Codec.encode(snapshot, httpStatus, finalizedAt);
    }

    public TransactionIntakeSnapshotReplay decode(String snapshotJson) {
        if (snapshotJson == null) {
            throw new InvalidTransactionIntakeSnapshotException();
        }

        final JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(snapshotJson);
        } catch (JsonProcessingException exception) {
            throw new InvalidTransactionIntakeSnapshotException(exception);
        }

        if (legacyCodec.matches(root)) {
            return legacyCodec.decode(root);
        }
        if (envelopeCodec.isCandidate(root)) {
            String responseSchemaVersion = requiredVersion(
                    root,
                    "responseSchemaVersion"
            );
            String codecVersion = requiredVersion(root, "codecVersion");
            if (TransactionIntakeSnapshotEnvelopeCodec
                    .RESPONSE_SCHEMA_VERSION.equals(responseSchemaVersion)
                    && TransactionIntakeSnapshotEnvelopeCodec
                    .CODEC_VERSION.equals(codecVersion)) {
                return envelopeCodec.decode(root);
            }
            if (TransactionIntakeSnapshotEnvelopeV2Codec
                    .RESPONSE_SCHEMA_VERSION.equals(responseSchemaVersion)
                    && TransactionIntakeSnapshotEnvelopeV2Codec
                    .CODEC_VERSION.equals(codecVersion)) {
                return envelopeV2Codec.decode(root);
            }
            throw new InvalidTransactionIntakeSnapshotException();
        }
        throw new InvalidTransactionIntakeSnapshotException();
    }

    private String requiredVersion(JsonNode root, String field) {
        JsonNode version = root.get(field);
        if (version == null
                || !version.isTextual()
                || version.textValue().isEmpty()) {
            throw new InvalidTransactionIntakeSnapshotException();
        }
        return version.textValue();
    }
}
