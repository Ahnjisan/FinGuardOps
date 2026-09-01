package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.fraudcase.validation.FraudCaseValidationException;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class FraudCaseResolutionRequestDeserializer
        extends JsonDeserializer<FraudCaseResolutionRequest> {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "finalDisposition", "reasonCode", "expectedVersion"
    );

    @Override
    public FraudCaseResolutionRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        requireObjectRoot(parser);
        String finalDisposition = null;
        String reasonCode = null;
        Long expectedVersion = null;
        Set<String> seen = new HashSet<>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = requireField(parser, seen);
            JsonToken token = parser.nextToken();
            switch (field) {
                case "finalDisposition" -> finalDisposition = readString(
                        parser, token, field, true
                );
                case "reasonCode" -> reasonCode = readString(
                        parser, token, field, true
                );
                case "expectedVersion" -> expectedVersion = readLong(
                        parser, token, field
                );
                default -> throw new IllegalStateException(
                        "Allowed resolution request field is not mapped"
                );
            }
        }
        if (parser.nextToken() != null) {
            throw mapping(parser, "$", "INVALID_JSON_TOKEN",
                    "Case resolution request contains trailing JSON content");
        }
        return new FraudCaseResolutionRequest(
                finalDisposition,
                reasonCode,
                expectedVersion
        );
    }

    @Override
    public FraudCaseResolutionRequest getNullValue(
            DeserializationContext context
    ) throws JsonMappingException {
        throw mapping(context.getParser(), "$", "INVALID_JSON_ROOT",
                "Case resolution request JSON root must be an object");
    }

    private void requireObjectRoot(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == null) {
            token = parser.nextToken();
        }
        if (token != JsonToken.START_OBJECT) {
            throw mapping(parser, "$", "INVALID_JSON_ROOT",
                    "Case resolution request JSON root must be an object");
        }
    }

    private String requireField(JsonParser parser, Set<String> seen)
            throws IOException {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
            throw mapping(parser, "$", "INVALID_JSON_TOKEN",
                    "Case resolution request JSON contains an invalid token");
        }
        String field = parser.currentName();
        if (!ALLOWED_FIELDS.contains(field)) {
            throw mapping(parser, field, "UNKNOWN_JSON_FIELD",
                    "Unknown case resolution request field");
        }
        if (!seen.add(field)) {
            throw mapping(parser, field, "DUPLICATE_JSON_FIELD",
                    "Duplicate case resolution request field");
        }
        return field;
    }

    private String readString(
            JsonParser parser,
            JsonToken token,
            String field,
            boolean nullable
    ) throws IOException {
        if (token == JsonToken.VALUE_NULL && nullable) {
            return null;
        }
        if (token != JsonToken.VALUE_STRING) {
            throw mapping(parser, field, "INVALID_JSON_TOKEN",
                    "Case resolution request field has an invalid JSON type");
        }
        return parser.getText();
    }

    private Long readLong(JsonParser parser, JsonToken token, String field)
            throws IOException {
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw mapping(parser, field, "INVALID_JSON_TOKEN",
                    "expectedVersion must be an integer");
        }
        try {
            return parser.getLongValue();
        } catch (InputCoercionException exception) {
            throw mapping(parser, field, "INVALID_JSON_TOKEN",
                    "expectedVersion must be a 64-bit integer");
        }
    }

    private JsonMappingException mapping(
            JsonParser parser,
            String field,
            String code,
            String message
    ) {
        FraudCaseValidationException cause =
                new FraudCaseValidationException(
                        FraudCaseValidationType.FORMAT,
                        field,
                        code,
                        message
                );
        return JsonMappingException.from(parser, message, cause);
    }
}
