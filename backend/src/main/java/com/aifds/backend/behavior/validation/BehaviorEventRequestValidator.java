package com.aifds.backend.behavior.validation;

import com.aifds.backend.behavior.command.ValidatedBehaviorEventCommand;
import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class BehaviorEventRequestValidator {

    public static final String REQUEST_REQUIRED = "REQUEST_REQUIRED";
    public static final String REQUIRED_FIELD = "REQUIRED_FIELD";
    public static final String INVALID_UUID_FORMAT = "INVALID_UUID_FORMAT";
    public static final String INVALID_UUID_VERSION = "INVALID_UUID_VERSION";
    public static final String INVALID_UUID_VARIANT = "INVALID_UUID_VARIANT";
    public static final String UNSUPPORTED_EVENT_TYPE =
            "UNSUPPORTED_EVENT_TYPE";
    public static final String INVALID_OCCURRED_AT_FORMAT =
            "INVALID_OCCURRED_AT_FORMAT";
    public static final String OCCURRED_AT_TOO_FAR_IN_FUTURE =
            "OCCURRED_AT_TOO_FAR_IN_FUTURE";
    public static final String INVALID_REFERENCE_LENGTH =
            "INVALID_REFERENCE_LENGTH";
    public static final String REFERENCE_HAS_SURROUNDING_WHITESPACE =
            "REFERENCE_HAS_SURROUNDING_WHITESPACE";
    public static final String DEVICE_REF_REQUIRED = "DEVICE_REF_REQUIRED";
    public static final String ACCOUNT_REF_REQUIRED = "ACCOUNT_REF_REQUIRED";
    public static final String TRANSACTION_ID_REQUIRED =
            "TRANSACTION_ID_REQUIRED";
    public static final String BENEFICIARY_REF_REQUIRED =
            "BENEFICIARY_REF_REQUIRED";
    public static final String BENEFICIARY_REF_FORBIDDEN =
            "BENEFICIARY_REF_FORBIDDEN";
    public static final String RELATED_TRANSACTION_CUSTOMER_MISMATCH =
            "RELATED_TRANSACTION_CUSTOMER_MISMATCH";
    public static final String RELATED_TRANSACTION_ACCOUNT_MISMATCH =
            "RELATED_TRANSACTION_ACCOUNT_MISMATCH";
    public static final String RELATED_TRANSACTION_TYPE_MISMATCH =
            "RELATED_TRANSACTION_TYPE_MISMATCH";

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
                    + "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$"
    );
    private static final Duration MAX_FUTURE_OFFSET = Duration.ofMinutes(5);

    private final Clock clock;
    private final Validator beanValidator;

    public BehaviorEventRequestValidator(Clock clock, Validator beanValidator) {
        this.clock = clock;
        this.beanValidator = beanValidator;
    }

    public ValidatedBehaviorEventCommand validate(
            BehaviorEventCreateRequest request
    ) {
        Instant validationNow = clock.instant();
        if (request == null) {
            throw format(
                    "$",
                    REQUEST_REQUIRED,
                    "Behavior event request is required"
            );
        }

        validateRequiredFields(request);

        UUID eventId = parseUuid("eventId", request.eventId());
        BehaviorEventType eventType = parseEventType(request.eventType());
        Instant occurredAt = parseOccurredAt(request.occurredAt());
        validateReference(
                "externalCustomerRef",
                request.externalCustomerRef()
        );
        validateOptionalReference("accountRef", request.accountRef());
        validateOptionalReference("deviceRef", request.deviceRef());
        UUID transactionId = parseOptionalUuid(
                "transactionId",
                request.transactionId()
        );
        validateOptionalReference(
                "beneficiaryRef",
                request.beneficiaryRef()
        );

        ValidatedBehaviorEventCommand command =
                new ValidatedBehaviorEventCommand(
                        eventId,
                        eventType,
                        occurredAt,
                        request.externalCustomerRef(),
                        request.accountRef(),
                        request.deviceRef(),
                        transactionId,
                        request.beneficiaryRef()
                );

        validateOccurredAtBoundary(occurredAt, validationNow);
        validateEventTypeFields(command);
        return command;
    }

    public void validateRelatedTransaction(
            ValidatedBehaviorEventCommand command,
            FinancialTransaction transaction
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");

        if (!command.externalCustomerRef().equals(
                transaction.getExternalCustomerRef()
        )) {
            throw domain(
                    "transactionId",
                    RELATED_TRANSACTION_CUSTOMER_MISMATCH,
                    "Related transaction customer does not match"
            );
        }

        if (command.accountRef() != null
                && !matchesEitherAccount(command.accountRef(), transaction)) {
            throw domain(
                    "transactionId",
                    RELATED_TRANSACTION_ACCOUNT_MISMATCH,
                    "Related transaction account does not match"
            );
        }

        switch (command.eventType()) {
            case TRANSFER_REQUESTED -> validateTransferTransaction(
                    command,
                    transaction
            );
            case ATM_WITHDRAWAL_REQUESTED -> validateAtmTransaction(
                    command,
                    transaction
            );
            default -> {
            }
        }
    }

    private void validateRequiredFields(BehaviorEventCreateRequest request) {
        beanValidator.validate(request).stream()
                .sorted(Comparator.comparing(
                        violation -> violation.getPropertyPath().toString()
                ))
                .findFirst()
                .ifPresent(violation -> {
                    throw requiredField(violation);
                });
    }

    private BehaviorEventValidationException requiredField(
            ConstraintViolation<BehaviorEventCreateRequest> violation
    ) {
        String field = violation.getPropertyPath().toString();
        return format(field, REQUIRED_FIELD, field + " is required");
    }

    private UUID parseOptionalUuid(String field, String value) {
        return value == null ? null : parseUuid(field, value);
    }

    private UUID parseUuid(String field, String value) {
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw format(
                    field,
                    INVALID_UUID_FORMAT,
                    field + " must use the canonical UUID string format"
            );
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    field,
                    INVALID_UUID_FORMAT,
                    field + " must be a UUID"
            );
        }
        if (uuid.version() != 4) {
            throw format(
                    field,
                    INVALID_UUID_VERSION,
                    field + " must be a UUID version 4"
            );
        }
        if (uuid.variant() != 2) {
            throw format(
                    field,
                    INVALID_UUID_VARIANT,
                    field + " must use the RFC 4122 variant"
            );
        }
        return uuid;
    }

    private BehaviorEventType parseEventType(String value) {
        try {
            return BehaviorEventType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "eventType",
                    UNSUPPORTED_EVENT_TYPE,
                    "eventType is not supported"
            );
        }
    }

    private Instant parseOccurredAt(String value) {
        if (!value.endsWith("Z")) {
            throw format(
                    "occurredAt",
                    INVALID_OCCURRED_AT_FORMAT,
                    "occurredAt must use UTC ISO-8601 Z notation"
            );
        }
        try {
            return Instant.parse(value);
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
        if (occurredAt.isAfter(validationNow.plus(MAX_FUTURE_OFFSET))) {
            throw domain(
                    "occurredAt",
                    OCCURRED_AT_TOO_FAR_IN_FUTURE,
                    "occurredAt must not exceed server time by more than 5 minutes"
            );
        }
    }

    private void validateReference(String field, String value) {
        int characterCount = value.codePointCount(0, value.length());
        if (characterCount < 1 || characterCount > 128) {
            throw format(
                    field,
                    INVALID_REFERENCE_LENGTH,
                    field + " length must be between 1 and 128"
            );
        }
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

    private void validateOptionalReference(String field, String value) {
        if (value != null) {
            validateReference(field, value);
        }
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    private void validateEventTypeFields(
            ValidatedBehaviorEventCommand command
    ) {
        switch (command.eventType()) {
            case LOGIN -> {
                requireDevice(command);
                forbidBeneficiary(command);
            }
            case LOGIN_FAILED, PASSWORD_CHANGED, OTP_REISSUED ->
                    forbidBeneficiary(command);
            case DEVICE_REGISTERED -> {
                requireDevice(command);
                forbidBeneficiary(command);
            }
            case BENEFICIARY_REGISTERED -> {
                requireAccount(command);
                requireBeneficiary(command);
            }
            case TRANSFER_LIMIT_CHANGED -> {
                requireAccount(command);
                forbidBeneficiary(command);
            }
            case TRANSFER_REQUESTED, ATM_WITHDRAWAL_REQUESTED -> {
                requireAccount(command);
                requireTransaction(command);
                forbidBeneficiary(command);
            }
        }
    }

    private void requireDevice(ValidatedBehaviorEventCommand command) {
        if (command.deviceRef() == null) {
            throw domain(
                    "deviceRef",
                    DEVICE_REF_REQUIRED,
                    "deviceRef is required for this eventType"
            );
        }
    }

    private void requireAccount(ValidatedBehaviorEventCommand command) {
        if (command.accountRef() == null) {
            throw domain(
                    "accountRef",
                    ACCOUNT_REF_REQUIRED,
                    "accountRef is required for this eventType"
            );
        }
    }

    private void requireTransaction(ValidatedBehaviorEventCommand command) {
        if (command.transactionId() == null) {
            throw domain(
                    "transactionId",
                    TRANSACTION_ID_REQUIRED,
                    "transactionId is required for this eventType"
            );
        }
    }

    private void requireBeneficiary(ValidatedBehaviorEventCommand command) {
        if (command.beneficiaryRef() == null) {
            throw domain(
                    "beneficiaryRef",
                    BENEFICIARY_REF_REQUIRED,
                    "beneficiaryRef is required for this eventType"
            );
        }
    }

    private void forbidBeneficiary(ValidatedBehaviorEventCommand command) {
        if (command.beneficiaryRef() != null) {
            throw domain(
                    "beneficiaryRef",
                    BENEFICIARY_REF_FORBIDDEN,
                    "beneficiaryRef is not allowed for this eventType"
            );
        }
    }

    private boolean matchesEitherAccount(
            String accountRef,
            FinancialTransaction transaction
    ) {
        return accountRef.equals(transaction.getSenderAccountRef())
                || accountRef.equals(transaction.getRecipientAccountRef());
    }

    private void validateTransferTransaction(
            ValidatedBehaviorEventCommand command,
            FinancialTransaction transaction
    ) {
        if (transaction.getTransactionType() != TransactionType.ACCOUNT_TRANSFER
                && transaction.getTransactionType()
                != TransactionType.OPEN_BANKING_TRANSFER) {
            throw domain(
                    "transactionId",
                    RELATED_TRANSACTION_TYPE_MISMATCH,
                    "Related transaction type is not allowed for this eventType"
            );
        }
        requireSenderAccount(command, transaction);
    }

    private void validateAtmTransaction(
            ValidatedBehaviorEventCommand command,
            FinancialTransaction transaction
    ) {
        if (transaction.getTransactionType() != TransactionType.ATM_WITHDRAWAL) {
            throw domain(
                    "transactionId",
                    RELATED_TRANSACTION_TYPE_MISMATCH,
                    "Related transaction type is not allowed for this eventType"
            );
        }
        requireSenderAccount(command, transaction);
    }

    private void requireSenderAccount(
            ValidatedBehaviorEventCommand command,
            FinancialTransaction transaction
    ) {
        if (!command.accountRef().equals(transaction.getSenderAccountRef())) {
            throw domain(
                    "transactionId",
                    RELATED_TRANSACTION_ACCOUNT_MISMATCH,
                    "Related transaction account does not match"
            );
        }
    }

    private BehaviorEventValidationException format(
            String field,
            String code,
            String message
    ) {
        return new BehaviorEventValidationException(
                BehaviorEventValidationType.FORMAT,
                field,
                code,
                message
        );
    }

    private BehaviorEventValidationException domain(
            String field,
            String code,
            String message
    ) {
        return new BehaviorEventValidationException(
                BehaviorEventValidationType.DOMAIN,
                field,
                code,
                message
        );
    }
}
