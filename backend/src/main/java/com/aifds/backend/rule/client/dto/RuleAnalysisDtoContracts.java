package com.aifds.backend.rule.client.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

final class RuleAnalysisDtoContracts {

    private static final Pattern CANONICAL_DECIMAL =
            Pattern.compile("^[1-9][0-9]{0,14}$");
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern REFERENCE =
            Pattern.compile("^\\S(?:.*\\S)?$|^\\S$");

    private RuleAnalysisDtoContracts() {
    }

    static void requireUuidV4(UUID value, String field) {
        if (value.version() != 4 || value.variant() != 2) {
            throw new IllegalArgumentException(field + " must be an RFC 4122 UUID v4");
        }
    }

    static void requireMicrosecondInstant(Instant value, String field) {
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    field + " precision must not exceed microseconds"
            );
        }
    }

    static void requireAmount(String value, String field) {
        if (!CANONICAL_DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a positive canonical decimal with at most 15 digits"
            );
        }
    }

    static void requireCurrencyCode(String value) {
        if (!CURRENCY_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "currencyCode must contain three uppercase ASCII characters"
            );
        }
    }

    static void requireReference(String value, String field) {
        if (value.length() > 128 || !REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be 1 to 128 characters without surrounding whitespace"
            );
        }
    }

    static void requireOptionalReference(String value, String field) {
        if (value != null) {
            requireReference(value, field);
        }
    }

    static void requireText(String value, String field) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
    }
}
