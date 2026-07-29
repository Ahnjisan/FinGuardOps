package com.aifds.backend.behavior.dto;

import com.aifds.backend.behavior.validation.BehaviorEventValidationException;
import com.aifds.backend.behavior.validation.BehaviorEventValidationType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class BehaviorEventCreateRequestDeserializer
        extends JsonDeserializer<BehaviorEventCreateRequest> {

    public static final String INVALID_JSON_ROOT = "INVALID_JSON_ROOT";
    public static final String UNKNOWN_JSON_FIELD = "UNKNOWN_JSON_FIELD";
    public static final String DUPLICATE_JSON_FIELD = "DUPLICATE_JSON_FIELD";
    public static final String INVALID_JSON_TOKEN = "INVALID_JSON_TOKEN";

    private static final String ROOT_FIELD = "$";
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "eventId",
            "eventType",
            "occurredAt",
            "externalCustomerRef",
            "accountRef",
            "deviceRef",
            "transactionId",
            "beneficiaryRef"
    );

    @Override
    public BehaviorEventCreateRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonToken rootToken = parser.currentToken();
        if (rootToken == null) {
            rootToken = parser.nextToken();
        }
        if (rootToken != JsonToken.START_OBJECT) {
            throw mappingException(
                    parser,
                    ROOT_FIELD,
                    INVALID_JSON_ROOT,
                    "Behavior event request JSON root must be an object"
            );
        }

        String eventId = null;
        String eventType = null;
        String occurredAt = null;
        String externalCustomerRef = null;
        String accountRef = null;
        String deviceRef = null;
        String transactionId = null;
        String beneficiaryRef = null;
        Set<String> seenFields = new HashSet<>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw mappingException(
                        parser,
                        ROOT_FIELD,
                        INVALID_JSON_TOKEN,
                        "Behavior event request JSON contains an invalid token"
                );
            }

            String field = parser.currentName();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw mappingException(
                        parser,
                        field,
                        UNKNOWN_JSON_FIELD,
                        "Unknown behavior event request field"
                );
            }
            if (!seenFields.add(field)) {
                throw mappingException(
                        parser,
                        field,
                        DUPLICATE_JSON_FIELD,
                        "Duplicate behavior event request field"
                );
            }

            JsonToken valueToken = parser.nextToken();
            if (valueToken != JsonToken.VALUE_STRING
                    && valueToken != JsonToken.VALUE_NULL) {
                throw mappingException(
                        parser,
                        field,
                        INVALID_JSON_TOKEN,
                        "Behavior event request field must be a string or null"
                );
            }
            String value = valueToken == JsonToken.VALUE_NULL
                    ? null
                    : parser.getText();

            switch (field) {
                case "eventId" -> eventId = value;
                case "eventType" -> eventType = value;
                case "occurredAt" -> occurredAt = value;
                case "externalCustomerRef" -> externalCustomerRef = value;
                case "accountRef" -> accountRef = value;
                case "deviceRef" -> deviceRef = value;
                case "transactionId" -> transactionId = value;
                case "beneficiaryRef" -> beneficiaryRef = value;
                default -> throw new IllegalStateException(
                        "Allowed behavior event field is not mapped"
                );
            }
        }

        return new BehaviorEventCreateRequest(
                eventId,
                eventType,
                occurredAt,
                externalCustomerRef,
                accountRef,
                deviceRef,
                transactionId,
                beneficiaryRef
        );
    }

    @Override
    public BehaviorEventCreateRequest getNullValue(
            DeserializationContext context
    ) throws JsonMappingException {
        throw mappingException(
                context.getParser(),
                ROOT_FIELD,
                INVALID_JSON_ROOT,
                "Behavior event request JSON root must be an object"
        );
    }

    private JsonMappingException mappingException(
            JsonParser parser,
            String field,
            String code,
            String message
    ) {
        BehaviorEventValidationException cause =
                new BehaviorEventValidationException(
                        BehaviorEventValidationType.FORMAT,
                        field,
                        code,
                        message
                );
        return JsonMappingException.from(parser, message, cause);
    }
}
