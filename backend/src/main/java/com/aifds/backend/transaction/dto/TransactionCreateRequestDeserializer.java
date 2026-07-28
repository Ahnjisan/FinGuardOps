package com.aifds.backend.transaction.dto;

import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class TransactionCreateRequestDeserializer
        extends JsonDeserializer<TransactionCreateRequest> {

    public static final String INVALID_JSON_ROOT = "INVALID_JSON_ROOT";
    public static final String UNKNOWN_JSON_FIELD = "UNKNOWN_JSON_FIELD";
    public static final String DUPLICATE_JSON_FIELD = "DUPLICATE_JSON_FIELD";
    public static final String INVALID_JSON_TOKEN = "INVALID_JSON_TOKEN";

    private static final String ROOT_FIELD = "$";
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "transactionId",
            "transactionType",
            "amount",
            "currencyCode",
            "occurredAt",
            "externalCustomerRef",
            "senderAccountRef",
            "recipientAccountRef",
            "channel",
            "deviceRef"
    );

    @Override
    public TransactionCreateRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonToken rootToken = parser.currentToken();
        if (rootToken == null) {
            rootToken = parser.nextToken();
        }
        if (rootToken != JsonToken.START_OBJECT) {
            throw formatMappingException(
                    parser,
                    ROOT_FIELD,
                    INVALID_JSON_ROOT,
                    "Transaction request JSON root must be an object"
            );
        }

        String transactionId = null;
        String transactionType = null;
        String amount = null;
        String currencyCode = null;
        String occurredAt = null;
        String externalCustomerRef = null;
        String senderAccountRef = null;
        String recipientAccountRef = null;
        String channel = null;
        String deviceRef = null;
        Set<String> seenFields = new HashSet<>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw formatMappingException(
                        parser,
                        ROOT_FIELD,
                        INVALID_JSON_TOKEN,
                        "Transaction request JSON object contains an invalid token"
                );
            }

            String field = parser.currentName();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw formatMappingException(
                        parser,
                        field,
                        UNKNOWN_JSON_FIELD,
                        "Unknown transaction request field"
                );
            }
            if (!seenFields.add(field)) {
                throw formatMappingException(
                        parser,
                        field,
                        DUPLICATE_JSON_FIELD,
                        "Duplicate transaction request field"
                );
            }

            JsonToken valueToken = parser.nextToken();
            if (valueToken != JsonToken.VALUE_STRING
                    && valueToken != JsonToken.VALUE_NULL) {
                throw formatMappingException(
                        parser,
                        field,
                        INVALID_JSON_TOKEN,
                        "Transaction request field must be a string or null"
                );
            }
            String value = valueToken == JsonToken.VALUE_NULL
                    ? null
                    : parser.getText();

            switch (field) {
                case "transactionId" -> transactionId = value;
                case "transactionType" -> transactionType = value;
                case "amount" -> amount = value;
                case "currencyCode" -> currencyCode = value;
                case "occurredAt" -> occurredAt = value;
                case "externalCustomerRef" -> externalCustomerRef = value;
                case "senderAccountRef" -> senderAccountRef = value;
                case "recipientAccountRef" -> recipientAccountRef = value;
                case "channel" -> channel = value;
                case "deviceRef" -> deviceRef = value;
                default -> throw new IllegalStateException(
                        "Allowed transaction request field is not mapped"
                );
            }
        }

        return new TransactionCreateRequest(
                transactionId,
                transactionType,
                amount,
                currencyCode,
                occurredAt,
                externalCustomerRef,
                senderAccountRef,
                recipientAccountRef,
                channel,
                deviceRef
        );
    }

    @Override
    public TransactionCreateRequest getNullValue(
            DeserializationContext context
    ) throws JsonMappingException {
        throw formatMappingException(
                context.getParser(),
                ROOT_FIELD,
                INVALID_JSON_ROOT,
                "Transaction request JSON root must be an object"
        );
    }

    private JsonMappingException formatMappingException(
            JsonParser parser,
            String field,
            String code,
            String message
    ) {
        TransactionValidationException cause = new TransactionValidationException(
                TransactionValidationType.FORMAT,
                field,
                code,
                message
        );
        return JsonMappingException.from(parser, message, cause);
    }
}
