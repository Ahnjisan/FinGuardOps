package com.aifds.backend.transaction.validation;

import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class TransactionRequestValidator {

    public static final String REQUEST_REQUIRED = "REQUEST_REQUIRED";
    public static final String REQUIRED_FIELD = "REQUIRED_FIELD";
    public static final String INVALID_UUID_FORMAT = "INVALID_UUID_FORMAT";
    public static final String INVALID_UUID_VERSION = "INVALID_UUID_VERSION";
    public static final String INVALID_UUID_VARIANT = "INVALID_UUID_VARIANT";
    public static final String UNSUPPORTED_TRANSACTION_TYPE =
            "UNSUPPORTED_TRANSACTION_TYPE";
    public static final String INVALID_AMOUNT_FORMAT = "INVALID_AMOUNT_FORMAT";
    public static final String AMOUNT_NOT_POSITIVE = "AMOUNT_NOT_POSITIVE";
    public static final String AMOUNT_OUT_OF_RANGE = "AMOUNT_OUT_OF_RANGE";
    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
    public static final String INVALID_OCCURRED_AT_FORMAT =
            "INVALID_OCCURRED_AT_FORMAT";
    public static final String OCCURRED_AT_TOO_FAR_IN_FUTURE =
            "OCCURRED_AT_TOO_FAR_IN_FUTURE";
    public static final String INVALID_REFERENCE_LENGTH =
            "INVALID_REFERENCE_LENGTH";
    public static final String REFERENCE_HAS_SURROUNDING_WHITESPACE =
            "REFERENCE_HAS_SURROUNDING_WHITESPACE";
    public static final String UNSUPPORTED_TRANSACTION_CHANNEL =
            "UNSUPPORTED_TRANSACTION_CHANNEL";
    public static final String RECIPIENT_ACCOUNT_REQUIRED =
            "RECIPIENT_ACCOUNT_REQUIRED";
    public static final String RECIPIENT_ACCOUNT_FORBIDDEN =
            "RECIPIENT_ACCOUNT_FORBIDDEN";
    public static final String TRANSACTION_CHANNEL_MISMATCH =
            "TRANSACTION_CHANNEL_MISMATCH";

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
                    + "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$"
    );
    private static final Pattern UNSIGNED_DECIMAL_INTEGER =
            Pattern.compile("^[0-9]+$");
    private static final BigDecimal MAX_AMOUNT =
            new BigDecimal("999999999999999");
    private static final Duration MAX_FUTURE_OFFSET = Duration.ofMinutes(5);

    private final Clock clock;
    private final Validator beanValidator;

    public TransactionRequestValidator(Clock clock, Validator beanValidator) {
        this.clock = clock;
        this.beanValidator = beanValidator;
    }

    public ValidatedTransactionCommand validate(TransactionCreateRequest request) {
        Instant validationNow = clock.instant();

        if (request == null) {
            throw format("$", REQUEST_REQUIRED, "Transaction request is required");
        }

        validateRequiredFields(request);

        UUID transactionId = parseTransactionId(request.transactionId());
        TransactionType transactionType =
                parseTransactionType(request.transactionType());
        BigDecimal amount = parseAmount(request.amount());
        validateCurrency(request.currencyCode());
        Instant occurredAt = parseOccurredAt(request.occurredAt());
        validateOccurredAtBoundary(occurredAt, validationNow);
        validateReference(
                "externalCustomerRef",
                request.externalCustomerRef()
        );
        validateReference("senderAccountRef", request.senderAccountRef());
        validateOptionalReference(
                "recipientAccountRef",
                request.recipientAccountRef()
        );
        TransactionChannel channel = parseChannel(request.channel());
        validateOptionalReference("deviceRef", request.deviceRef());

        ParsedTransactionRequest parsed = new ParsedTransactionRequest(
                transactionId,
                transactionType,
                amount,
                request.currencyCode(),
                occurredAt,
                request.externalCustomerRef(),
                request.senderAccountRef(),
                request.recipientAccountRef(),
                channel,
                request.deviceRef()
        );

        validateDomain(parsed);
        return new ValidatedTransactionCommand(
                parsed.transactionId(),
                parsed.transactionType(),
                parsed.amount(),
                parsed.currencyCode(),
                parsed.occurredAt(),
                parsed.externalCustomerRef(),
                parsed.senderAccountRef(),
                parsed.recipientAccountRef(),
                parsed.channel(),
                parsed.deviceRef()
        );
    }

    private void validateRequiredFields(TransactionCreateRequest request) {
        beanValidator.validate(request).stream()
                .sorted(Comparator.comparing(
                        violation -> violation.getPropertyPath().toString()
                ))
                .findFirst()
                .ifPresent(violation -> {
                    throw requiredField(violation);
                });
    }

    private TransactionValidationException requiredField(
            ConstraintViolation<TransactionCreateRequest> violation
    ) {
        String field = violation.getPropertyPath().toString();
        return format(field, REQUIRED_FIELD, field + " is required");
    }

    private UUID parseTransactionId(String rawTransactionId) {
        if (!CANONICAL_UUID.matcher(rawTransactionId).matches()) {
            throw format(
                    "transactionId",
                    INVALID_UUID_FORMAT,
                    "transactionId must use the canonical UUID string format"
            );
        }

        UUID transactionId;
        try {
            transactionId = UUID.fromString(rawTransactionId);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "transactionId",
                    INVALID_UUID_FORMAT,
                    "transactionId must be a UUID"
            );
        }

        if (transactionId.version() != 4) {
            throw format(
                    "transactionId",
                    INVALID_UUID_VERSION,
                    "transactionId must be a UUID version 4"
            );
        }
        if (transactionId.variant() != 2) {
            throw format(
                    "transactionId",
                    INVALID_UUID_VARIANT,
                    "transactionId must use the RFC 4122 variant"
            );
        }
        return transactionId;
    }

    private TransactionType parseTransactionType(String rawTransactionType) {
        try {
            return TransactionType.valueOf(rawTransactionType);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "transactionType",
                    UNSUPPORTED_TRANSACTION_TYPE,
                    "transactionType is not supported"
            );
        }
    }

    private BigDecimal parseAmount(String rawAmount) {
        if (!UNSIGNED_DECIMAL_INTEGER.matcher(rawAmount).matches()) {
            throw format(
                    "amount",
                    INVALID_AMOUNT_FORMAT,
                    "amount must be an unsigned decimal integer string"
            );
        }

        BigDecimal amount = new BigDecimal(rawAmount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw format(
                    "amount",
                    AMOUNT_NOT_POSITIVE,
                    "amount must be greater than zero"
            );
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw format(
                    "amount",
                    AMOUNT_OUT_OF_RANGE,
                    "amount exceeds the NUMERIC(19,4) integer range"
            );
        }
        return amount;
    }

    private void validateCurrency(String currencyCode) {
        if (!"KRW".equals(currencyCode)) {
            throw format(
                    "currencyCode",
                    UNSUPPORTED_CURRENCY,
                    "currencyCode must be KRW"
            );
        }
    }

    private Instant parseOccurredAt(String rawOccurredAt) {
        if (!rawOccurredAt.endsWith("Z")) {
            throw format(
                    "occurredAt",
                    INVALID_OCCURRED_AT_FORMAT,
                    "occurredAt must use UTC ISO-8601 Z notation"
            );
        }

        try {
            return Instant.parse(rawOccurredAt);
        } catch (DateTimeException exception) {
            throw format(
                    "occurredAt",
                    INVALID_OCCURRED_AT_FORMAT,
                    "occurredAt must use UTC ISO-8601 Z notation"
            );
        }
    }

    private void validateOccurredAtBoundary(
            Instant occurredAt,
            Instant validationNow
    ) {
        Instant latestAllowed = validationNow.plus(MAX_FUTURE_OFFSET);
        if (occurredAt.isAfter(latestAllowed)) {
            throw format(
                    "occurredAt",
                    OCCURRED_AT_TOO_FAR_IN_FUTURE,
                    "occurredAt must not exceed the server time by more than 5 minutes"
            );
        }
    }

    private void validateReference(String field, String value) {
        validateReferenceLength(field, value);
        validateReferenceWhitespace(field, value);
    }

    private void validateOptionalReference(String field, String value) {
        if (value != null) {
            validateReference(field, value);
        }
    }

    private void validateReferenceLength(String field, String value) {
        int characterCount = value.codePointCount(0, value.length());
        if (characterCount < 1 || characterCount > 128) {
            throw format(
                    field,
                    INVALID_REFERENCE_LENGTH,
                    field + " length must be between 1 and 128"
            );
        }
    }

    private void validateReferenceWhitespace(String field, String value) {
        int firstCodePoint = value.codePointAt(0);
        int lastCodePoint = value.codePointBefore(value.length());
        if (isWhitespace(firstCodePoint) || isWhitespace(lastCodePoint)) {
            throw format(
                    field,
                    REFERENCE_HAS_SURROUNDING_WHITESPACE,
                    field + " must not have surrounding whitespace"
            );
        }
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    private TransactionChannel parseChannel(String rawChannel) {
        try {
            return TransactionChannel.valueOf(rawChannel);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "channel",
                    UNSUPPORTED_TRANSACTION_CHANNEL,
                    "channel is not supported"
            );
        }
    }

    private void validateDomain(ParsedTransactionRequest parsed) {
        switch (parsed.transactionType()) {
            case ACCOUNT_TRANSFER -> validateTransferDomain(
                    parsed,
                    TransactionChannel.MOBILE_BANKING
            );
            case OPEN_BANKING_TRANSFER -> validateTransferDomain(
                    parsed,
                    TransactionChannel.OPEN_BANKING
            );
            case ATM_WITHDRAWAL -> validateNoRecipientDomain(
                    parsed,
                    TransactionChannel.ATM
            );
            case LOAN_DISBURSED -> validateNoRecipientDomain(
                    parsed,
                    TransactionChannel.CORE_BANKING
            );
        }
    }

    private void validateTransferDomain(
            ParsedTransactionRequest parsed,
            TransactionChannel expectedChannel
    ) {
        if (parsed.recipientAccountRef() == null) {
            throw domain(
                    "recipientAccountRef",
                    RECIPIENT_ACCOUNT_REQUIRED,
                    "recipientAccountRef is required for transfer transactions"
            );
        }
        validateExpectedChannel(parsed.channel(), expectedChannel);
    }

    private void validateNoRecipientDomain(
            ParsedTransactionRequest parsed,
            TransactionChannel expectedChannel
    ) {
        if (parsed.recipientAccountRef() != null) {
            throw domain(
                    "recipientAccountRef",
                    RECIPIENT_ACCOUNT_FORBIDDEN,
                    "recipientAccountRef is not allowed for this transaction type"
            );
        }
        validateExpectedChannel(parsed.channel(), expectedChannel);
    }

    private void validateExpectedChannel(
            TransactionChannel actual,
            TransactionChannel expected
    ) {
        if (actual != expected) {
            throw domain(
                    "channel",
                    TRANSACTION_CHANNEL_MISMATCH,
                    "channel does not match transactionType"
            );
        }
    }

    private TransactionValidationException format(
            String field,
            String code,
            String message
    ) {
        return new TransactionValidationException(
                TransactionValidationType.FORMAT,
                field,
                code,
                message
        );
    }

    private TransactionValidationException domain(
            String field,
            String code,
            String message
    ) {
        return new TransactionValidationException(
                TransactionValidationType.DOMAIN,
                field,
                code,
                message
        );
    }

    private record ParsedTransactionRequest(
            UUID transactionId,
            TransactionType transactionType,
            BigDecimal amount,
            String currencyCode,
            Instant occurredAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            TransactionChannel channel,
            String deviceRef
    ) {
    }
}
