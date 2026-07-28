package com.aifds.backend.transaction.validation;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class IdempotencyKeyValidator {

    public static final String IDEMPOTENCY_KEY_REQUIRED =
            "IDEMPOTENCY_KEY_REQUIRED";
    public static final String IDEMPOTENCY_KEY_INVALID_LENGTH =
            "IDEMPOTENCY_KEY_INVALID_LENGTH";
    public static final String IDEMPOTENCY_KEY_INVALID_CHARACTERS =
            "IDEMPOTENCY_KEY_INVALID_CHARACTERS";

    private static final String FIELD = "Idempotency-Key";
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;
    private static final Pattern ALLOWED_CHARACTERS =
            Pattern.compile("^[A-Za-z0-9._:-]+$");

    public String validate(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw format(
                    IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key is required"
            );
        }
        if (idempotencyKey.length() < MIN_LENGTH
                || idempotencyKey.length() > MAX_LENGTH) {
            throw format(
                    IDEMPOTENCY_KEY_INVALID_LENGTH,
                    "Idempotency-Key length must be between 8 and 128"
            );
        }
        if (!ALLOWED_CHARACTERS.matcher(idempotencyKey).matches()) {
            throw format(
                    IDEMPOTENCY_KEY_INVALID_CHARACTERS,
                    "Idempotency-Key contains an unsupported character"
            );
        }
        return idempotencyKey;
    }

    private TransactionValidationException format(String code, String message) {
        return new TransactionValidationException(
                TransactionValidationType.FORMAT,
                FIELD,
                code,
                message
        );
    }
}
