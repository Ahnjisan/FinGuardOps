package com.aifds.backend.fraudcase.dto;

import com.aifds.backend.fraudcase.validation.InvestigationNoteValidationException;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidationType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class InvestigationNoteCreateRequestDeserializer
        extends JsonDeserializer<InvestigationNoteCreateRequest> {

    private static final Set<String> ALLOWED = Set.of("content", "expectedVersion");

    @Override
    public InvestigationNoteCreateRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw mapping(parser, "$", "INVALID_JSON_ROOT", "Note request root must be an object");
        }
        String content = null;
        Long expectedVersion = null;
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw mapping(parser, "$", "INVALID_JSON_TOKEN", "Note request contains an invalid token");
            }
            String field = parser.currentName();
            if (!ALLOWED.contains(field)) {
                throw mapping(parser, field, "UNKNOWN_JSON_FIELD", "Unknown note request field");
            }
            if (!seen.add(field)) {
                throw mapping(parser, field, "DUPLICATE_JSON_FIELD", "Duplicate note request field");
            }
            JsonToken valueToken = parser.nextToken();
            if ("content".equals(field)) {
                if (valueToken == JsonToken.VALUE_NULL) {
                    content = null;
                } else if (valueToken == JsonToken.VALUE_STRING) {
                    content = parser.getText();
                } else {
                    throw mapping(parser, field, "INVALID_JSON_TOKEN", "content must be a string");
                }
            } else {
                if (valueToken == JsonToken.VALUE_NULL) {
                    expectedVersion = null;
                } else if (valueToken != JsonToken.VALUE_NUMBER_INT) {
                    throw mapping(parser, field, "INVALID_JSON_TOKEN", "expectedVersion must be an integer");
                } else {
                    try {
                        expectedVersion = parser.getLongValue();
                    } catch (InputCoercionException exception) {
                        throw mapping(parser, field, "INVALID_JSON_TOKEN", "expectedVersion must be a 64-bit integer");
                    }
                }
            }
        }
        if (parser.nextToken() != null) {
            throw mapping(parser, "$", "INVALID_JSON_TOKEN", "Note request contains trailing JSON content");
        }
        return new InvestigationNoteCreateRequest(content, expectedVersion);
    }

    @Override
    public InvestigationNoteCreateRequest getNullValue(DeserializationContext context)
            throws JsonMappingException {
        throw mapping(context.getParser(), "$", "INVALID_JSON_ROOT", "Note request root must be an object");
    }

    private JsonMappingException mapping(
            JsonParser parser,
            String field,
            String code,
            String message
    ) {
        return JsonMappingException.from(parser, message,
                new InvestigationNoteValidationException(
                        InvestigationNoteValidationType.FORMAT, field, code, message
                ));
    }
}
