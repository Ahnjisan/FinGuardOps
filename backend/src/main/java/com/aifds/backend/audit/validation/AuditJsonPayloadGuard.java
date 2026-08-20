package com.aifds.backend.audit.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;

public final class AuditJsonPayloadGuard {

    public static final int MAX_JSON_BYTES = 8 * 1024;
    public static final int MAX_FIELDS_PER_OBJECT = 8;

    private static final int MAX_FIELD_NAME_CHARACTERS = 64;
    private static final int MAX_TEXT_VALUE_CHARACTERS = MAX_JSON_BYTES;

    private AuditJsonPayloadGuard() {
    }

    public static void validatePayloads(
            JsonNode beforeValueSummary,
            JsonNode afterValueSummary,
            JsonNode metadata
    ) {
        int remainingBytes = MAX_JSON_BYTES;
        int beforeBytes = postgresqlJsonbTextUtf8Bytes(
                beforeValueSummary,
                "beforeValueSummary",
                true,
                remainingBytes
        );
        remainingBytes -= beforeBytes;
        int afterBytes = postgresqlJsonbTextUtf8Bytes(
                afterValueSummary,
                "afterValueSummary",
                true,
                remainingBytes
        );
        remainingBytes -= afterBytes;
        postgresqlJsonbTextUtf8Bytes(
                metadata,
                "metadata",
                false,
                remainingBytes
        );
    }

    public static int postgresqlJsonbTextUtf8Bytes(
            JsonNode value,
            String fieldName,
            boolean nullable
    ) {
        return postgresqlJsonbTextUtf8Bytes(
                value,
                fieldName,
                nullable,
                MAX_JSON_BYTES
        );
    }

    private static int postgresqlJsonbTextUtf8Bytes(
            JsonNode value,
            String fieldName,
            boolean nullable,
            int byteBudget
    ) {
        if (value == null) {
            if (nullable) {
                return 0;
            }
            throw new IllegalArgumentException(
                    fieldName + " must not be null"
            );
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a JSON object"
            );
        }
        if (value.size() > MAX_FIELDS_PER_OBJECT) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed 8 fields"
            );
        }
        if (byteBudget < 2) {
            throw new IllegalArgumentException(
                    "audit JSON payload must not exceed 8192 bytes"
            );
        }

        int bytes = 2;
        int fieldIndex = 0;
        Iterator<String> fieldNames = value.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            validateFieldName(field, fieldName);
            JsonNode scalar = value.get(field);
            int scalarBytes = scalarBytes(scalar, fieldName);
            if (fieldIndex > 0) {
                bytes = addBounded(bytes, 2, byteBudget);
            }
            bytes = addBounded(bytes, field.length() + 2, byteBudget);
            bytes = addBounded(bytes, 2, byteBudget);
            bytes = addBounded(bytes, scalarBytes, byteBudget);
            fieldIndex++;
        }
        return bytes;
    }

    private static void validateFieldName(
            String field,
            String rootFieldName
    ) {
        if (field.isEmpty()
                || field.length() > MAX_FIELD_NAME_CHARACTERS) {
            throw new IllegalArgumentException(
                    rootFieldName + " contains an invalid field name"
            );
        }
        for (int index = 0; index < field.length(); index++) {
            char character = field.charAt(index);
            if (!isAsciiLetterOrDigit(character)) {
                throw new IllegalArgumentException(
                        rootFieldName + " field names must be ASCII alphanumeric"
                );
            }
        }
    }

    private static int scalarBytes(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    fieldName + " values must be non-null scalars"
            );
        }
        if (value.isContainerNode()) {
            throw new IllegalArgumentException(
                    fieldName + " values must not be arrays or nested objects"
            );
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if (text.length() > MAX_TEXT_VALUE_CHARACTERS) {
                throw new IllegalArgumentException(
                        fieldName + " contains an oversized scalar value"
                );
            }
            for (int index = 0; index < text.length(); index++) {
                if (!isApprovedAsciiScalarCharacter(text.charAt(index))) {
                    throw new IllegalArgumentException(
                            fieldName + " text values must use approved ASCII"
                    );
                }
            }
            return text.length() + 2;
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? 4 : 5;
        }
        if (value.isIntegralNumber()) {
            if (value.canConvertToInt() && value.intValue() > 0) {
                return Integer.toString(value.intValue()).length();
            }
            throw new IllegalArgumentException(
                    fieldName + " integral values must be positive integers"
            );
        }
        throw new IllegalArgumentException(
                fieldName + " values must be approved scalar types"
        );
    }

    private static int addBounded(
            int current,
            int additional,
            int byteBudget
    ) {
        int result = current + additional;
        if (result > byteBudget) {
            throw new IllegalArgumentException(
                    "audit JSON payload must not exceed 8192 bytes"
            );
        }
        return result;
    }

    private static boolean isApprovedAsciiScalarCharacter(char character) {
        return isAsciiLetterOrDigit(character)
                || character == '.'
                || character == '_'
                || character == ':'
                || character == '-';
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9');
    }
}
