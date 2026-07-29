package com.aifds.backend.behavior.validation;

import com.aifds.backend.behavior.command.ValidatedBehaviorEventCommand;
import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorEventRequestValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-29T04:10:00Z");
    private static final UUID EVENT_ID = UUID.fromString(
            "e54cbf7e-d857-4ca0-bff3-8d4321b7722a"
    );
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final Validator BEAN_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final BehaviorEventRequestValidator validator =
            new BehaviorEventRequestValidator(
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    BEAN_VALIDATOR
            );

    @ParameterizedTest
    @EnumSource(BehaviorEventType.class)
    void supportsAllNineEventTypes(BehaviorEventType eventType) {
        ValidatedBehaviorEventCommand command = validator.validate(
                validRequest(eventType)
        );

        assertThat(command.eventType()).isEqualTo(eventType);
        assertThat(command.eventId()).isEqualTo(EVENT_ID);
        assertThat(command.occurredAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @MethodSource("missingCommonFields")
    void rejectsMissingCommonFields(
            BehaviorEventCreateRequest request,
            String field
    ) {
        assertFailure(
                request,
                BehaviorEventValidationType.FORMAT,
                field,
                BehaviorEventRequestValidator.REQUIRED_FIELD
        );
    }

    static Stream<Arguments> missingCommonFields() {
        BehaviorEventCreateRequest base =
                request(BehaviorEventType.LOGIN_FAILED);
        return Stream.of(
                Arguments.of(copy(base, null, base.eventType(),
                        base.occurredAt(), base.externalCustomerRef()),
                        "eventId"),
                Arguments.of(copy(base, base.eventId(), null,
                        base.occurredAt(), base.externalCustomerRef()),
                        "eventType"),
                Arguments.of(copy(base, base.eventId(), base.eventType(),
                        null, base.externalCustomerRef()), "occurredAt"),
                Arguments.of(copy(base, base.eventId(), base.eventType(),
                        base.occurredAt(), null), "externalCustomerRef")
        );
    }

    @Test
    void validatesCanonicalUuidV4AndRfc4122VariantForBothIds() {
        BehaviorEventCreateRequest base =
                validRequest(BehaviorEventType.TRANSFER_REQUESTED);

        assertFailure(
                withEventId(base, "1-1-1-1-1"),
                BehaviorEventValidationType.FORMAT,
                "eventId",
                BehaviorEventRequestValidator.INVALID_UUID_FORMAT
        );
        assertFailure(
                withEventId(
                        base,
                        "e54cbf7e-d857-1ca0-bff3-8d4321b7722a"
                ),
                BehaviorEventValidationType.FORMAT,
                "eventId",
                BehaviorEventRequestValidator.INVALID_UUID_VERSION
        );
        assertFailure(
                withEventId(
                        base,
                        "e54cbf7e-d857-4ca0-1ff3-8d4321b7722a"
                ),
                BehaviorEventValidationType.FORMAT,
                "eventId",
                BehaviorEventRequestValidator.INVALID_UUID_VARIANT
        );
        assertFailure(
                withTransactionId(
                        base,
                        "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"
                ),
                BehaviorEventValidationType.FORMAT,
                "transactionId",
                BehaviorEventRequestValidator.INVALID_UUID_VERSION
        );
    }

    @Test
    void rejectsUnsupportedEnumAsFormatError() {
        BehaviorEventCreateRequest base =
                request(BehaviorEventType.LOGIN_FAILED);
        assertFailure(
                copy(
                        base,
                        base.eventId(),
                        "UNKNOWN",
                        base.occurredAt(),
                        base.externalCustomerRef()
                ),
                BehaviorEventValidationType.FORMAT,
                "eventType",
                BehaviorEventRequestValidator.UNSUPPORTED_EVENT_TYPE
        );
    }

    @Test
    void acceptsUtcZAndExactlyFiveMinutesButRejectsOffsetAndLaterFuture() {
        BehaviorEventCreateRequest base =
                request(BehaviorEventType.LOGIN_FAILED);

        assertThat(validator.validate(withOccurredAt(
                base,
                NOW.plusSeconds(300).toString()
        )).occurredAt()).isEqualTo(NOW.plusSeconds(300));

        assertFailure(
                withOccurredAt(base, "2026-07-29T13:10:00+09:00"),
                BehaviorEventValidationType.FORMAT,
                "occurredAt",
                BehaviorEventRequestValidator.INVALID_OCCURRED_AT_FORMAT
        );
        assertFailure(
                withOccurredAt(base, NOW.plusSeconds(301).toString()),
                BehaviorEventValidationType.DOMAIN,
                "occurredAt",
                BehaviorEventRequestValidator.OCCURRED_AT_TOO_FAR_IN_FUTURE
        );
    }

    @Test
    void readsInjectedClockExactlyOnce() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        BehaviorEventRequestValidator clockValidator =
                new BehaviorEventRequestValidator(clock, BEAN_VALIDATOR);

        clockValidator.validate(request(BehaviorEventType.LOGIN_FAILED));

        verify(clock, times(1)).instant();
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void validatesReferenceLengthsAndWhitespace(
            String value,
            String expectedCode
    ) {
        BehaviorEventCreateRequest base =
                request(BehaviorEventType.LOGIN_FAILED);
        BehaviorEventCreateRequest changed = new BehaviorEventCreateRequest(
                base.eventId(),
                base.eventType(),
                base.occurredAt(),
                value,
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        );
        assertFailure(
                changed,
                BehaviorEventValidationType.FORMAT,
                "externalCustomerRef",
                expectedCode
        );
    }

    static Stream<Arguments> invalidReferences() {
        return Stream.of(
                Arguments.of("", BehaviorEventRequestValidator.INVALID_REFERENCE_LENGTH),
                Arguments.of(" ".repeat(2),
                        BehaviorEventRequestValidator.REFERENCE_HAS_SURROUNDING_WHITESPACE),
                Arguments.of(" padded",
                        BehaviorEventRequestValidator.REFERENCE_HAS_SURROUNDING_WHITESPACE),
                Arguments.of("padded ",
                        BehaviorEventRequestValidator.REFERENCE_HAS_SURROUNDING_WHITESPACE),
                Arguments.of("x".repeat(129),
                        BehaviorEventRequestValidator.INVALID_REFERENCE_LENGTH)
        );
    }

    @Test
    void acceptsOneAndOneHundredTwentyEightCharacterReferences() {
        BehaviorEventCreateRequest one =
                request(BehaviorEventType.LOGIN_FAILED);
        BehaviorEventCreateRequest max = new BehaviorEventCreateRequest(
                one.eventId(),
                one.eventType(),
                one.occurredAt(),
                "x".repeat(128),
                "a".repeat(128),
                "d".repeat(128),
                null,
                null
        );

        assertThat(validator.validate(one).externalCustomerRef()).isEqualTo("c");
        assertThat(validator.validate(max).accountRef())
                .hasSize(128);
    }

    @ParameterizedTest
    @MethodSource("invalidTypeConditions")
    void enforcesEventSpecificRequiredAndForbiddenFields(
            BehaviorEventCreateRequest request,
            String field,
            String code
    ) {
        assertFailure(
                request,
                BehaviorEventValidationType.DOMAIN,
                field,
                code
        );
    }

    static Stream<Arguments> invalidTypeConditions() {
        return Stream.of(
                Arguments.of(request(BehaviorEventType.LOGIN),
                        "deviceRef",
                        BehaviorEventRequestValidator.DEVICE_REF_REQUIRED),
                Arguments.of(request(BehaviorEventType.DEVICE_REGISTERED),
                        "deviceRef",
                        BehaviorEventRequestValidator.DEVICE_REF_REQUIRED),
                Arguments.of(request(BehaviorEventType.BENEFICIARY_REGISTERED),
                        "accountRef",
                        BehaviorEventRequestValidator.ACCOUNT_REF_REQUIRED),
                Arguments.of(withAccount(
                                request(BehaviorEventType.BENEFICIARY_REGISTERED),
                                "account"),
                        "beneficiaryRef",
                        BehaviorEventRequestValidator.BENEFICIARY_REF_REQUIRED),
                Arguments.of(request(BehaviorEventType.TRANSFER_LIMIT_CHANGED),
                        "accountRef",
                        BehaviorEventRequestValidator.ACCOUNT_REF_REQUIRED),
                Arguments.of(request(BehaviorEventType.TRANSFER_REQUESTED),
                        "accountRef",
                        BehaviorEventRequestValidator.ACCOUNT_REF_REQUIRED),
                Arguments.of(withAccount(
                                request(BehaviorEventType.TRANSFER_REQUESTED),
                                "account"),
                        "transactionId",
                        BehaviorEventRequestValidator.TRANSACTION_ID_REQUIRED),
                Arguments.of(withBeneficiary(
                                request(BehaviorEventType.LOGIN_FAILED),
                                "beneficiary"),
                        "beneficiaryRef",
                        BehaviorEventRequestValidator.BENEFICIARY_REF_FORBIDDEN)
        );
    }

    @Test
    void treatsMissingAndExplicitOptionalNullAsCommandNull() {
        ValidatedBehaviorEventCommand command = validator.validate(
                request(BehaviorEventType.LOGIN_FAILED)
        );

        assertThat(command.accountRef()).isNull();
        assertThat(command.deviceRef()).isNull();
        assertThat(command.transactionId()).isNull();
        assertThat(command.beneficiaryRef()).isNull();
    }

    @Test
    void validatesRelatedTransactionCustomerAccountAndGeneralAccountMatching() {
        ValidatedBehaviorEventCommand command = validator.validate(
                new BehaviorEventCreateRequest(
                        EVENT_ID.toString(),
                        BehaviorEventType.LOGIN_FAILED.name(),
                        NOW.toString(),
                        "customer",
                        "recipient",
                        null,
                        TRANSACTION_ID.toString(),
                        null
                )
        );
        FinancialTransaction matching = transaction(
                TransactionType.ACCOUNT_TRANSFER,
                "customer",
                "sender",
                "recipient"
        );

        validator.validateRelatedTransaction(command, matching);

        assertThatThrownBy(() -> validator.validateRelatedTransaction(
                command,
                transaction(
                        TransactionType.ACCOUNT_TRANSFER,
                        "other",
                        "sender",
                        "recipient"
                )
        )).isInstanceOfSatisfying(
                BehaviorEventValidationException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(
                        BehaviorEventRequestValidator
                                .RELATED_TRANSACTION_CUSTOMER_MISMATCH
                )
        );
        assertThatThrownBy(() -> validator.validateRelatedTransaction(
                command,
                transaction(
                        TransactionType.ACCOUNT_TRANSFER,
                        "customer",
                        "sender",
                        "different"
                )
        )).isInstanceOfSatisfying(
                BehaviorEventValidationException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(
                        BehaviorEventRequestValidator
                                .RELATED_TRANSACTION_ACCOUNT_MISMATCH
                )
        );
    }

    @Test
    void validatesTransferAndAtmTypesAndSenderAccount() {
        ValidatedBehaviorEventCommand transfer = validator.validate(
                validRequest(BehaviorEventType.TRANSFER_REQUESTED)
        );
        validator.validateRelatedTransaction(
                transfer,
                transaction(
                        TransactionType.OPEN_BANKING_TRANSFER,
                        "c",
                        "account",
                        "recipient"
                )
        );

        assertRelatedFailure(
                transfer,
                transaction(
                        TransactionType.ATM_WITHDRAWAL,
                        "c",
                        "account",
                        null
                ),
                BehaviorEventRequestValidator.RELATED_TRANSACTION_TYPE_MISMATCH
        );
        assertRelatedFailure(
                transfer,
                transaction(
                        TransactionType.ACCOUNT_TRANSFER,
                        "c",
                        "recipient",
                        "account"
                ),
                BehaviorEventRequestValidator.RELATED_TRANSACTION_ACCOUNT_MISMATCH
        );

        ValidatedBehaviorEventCommand atm = validator.validate(
                validRequest(BehaviorEventType.ATM_WITHDRAWAL_REQUESTED)
        );
        validator.validateRelatedTransaction(
                atm,
                transaction(
                        TransactionType.ATM_WITHDRAWAL,
                        "c",
                        "account",
                        null
                )
        );
    }

    private void assertRelatedFailure(
            ValidatedBehaviorEventCommand command,
            FinancialTransaction transaction,
            String code
    ) {
        assertThatThrownBy(() ->
                validator.validateRelatedTransaction(command, transaction)
        ).isInstanceOfSatisfying(
                BehaviorEventValidationException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code)
        );
    }

    private static BehaviorEventCreateRequest validRequest(
            BehaviorEventType eventType
    ) {
        BehaviorEventCreateRequest base = request(eventType);
        return switch (eventType) {
            case LOGIN, DEVICE_REGISTERED -> withDevice(base, "device");
            case BENEFICIARY_REGISTERED -> withBeneficiary(
                    withAccount(base, "account"),
                    "beneficiary"
            );
            case TRANSFER_LIMIT_CHANGED -> withAccount(base, "account");
            case TRANSFER_REQUESTED, ATM_WITHDRAWAL_REQUESTED ->
                    withTransactionId(
                            withAccount(base, "account"),
                            TRANSACTION_ID.toString()
                    );
            default -> base;
        };
    }

    private static BehaviorEventCreateRequest request(
            BehaviorEventType eventType
    ) {
        return new BehaviorEventCreateRequest(
                EVENT_ID.toString(),
                eventType.name(),
                NOW.toString(),
                "c",
                null,
                null,
                null,
                null
        );
    }

    private static BehaviorEventCreateRequest copy(
            BehaviorEventCreateRequest base,
            String eventId,
            String eventType,
            String occurredAt,
            String externalCustomerRef
    ) {
        return new BehaviorEventCreateRequest(
                eventId,
                eventType,
                occurredAt,
                externalCustomerRef,
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        );
    }

    private static BehaviorEventCreateRequest withEventId(
            BehaviorEventCreateRequest base,
            String value
    ) {
        return copy(
                base,
                value,
                base.eventType(),
                base.occurredAt(),
                base.externalCustomerRef()
        );
    }

    private static BehaviorEventCreateRequest withOccurredAt(
            BehaviorEventCreateRequest base,
            String value
    ) {
        return copy(
                base,
                base.eventId(),
                base.eventType(),
                value,
                base.externalCustomerRef()
        );
    }

    private static BehaviorEventCreateRequest withAccount(
            BehaviorEventCreateRequest base,
            String value
    ) {
        return new BehaviorEventCreateRequest(
                base.eventId(),
                base.eventType(),
                base.occurredAt(),
                base.externalCustomerRef(),
                value,
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        );
    }

    private static BehaviorEventCreateRequest withDevice(
            BehaviorEventCreateRequest base,
            String value
    ) {
        return new BehaviorEventCreateRequest(
                base.eventId(),
                base.eventType(),
                base.occurredAt(),
                base.externalCustomerRef(),
                base.accountRef(),
                value,
                base.transactionId(),
                base.beneficiaryRef()
        );
    }

    private static BehaviorEventCreateRequest withTransactionId(
            BehaviorEventCreateRequest base,
            String value
    ) {
        return new BehaviorEventCreateRequest(
                base.eventId(),
                base.eventType(),
                base.occurredAt(),
                base.externalCustomerRef(),
                base.accountRef(),
                base.deviceRef(),
                value,
                base.beneficiaryRef()
        );
    }

    private static BehaviorEventCreateRequest withBeneficiary(
            BehaviorEventCreateRequest base,
            String value
    ) {
        return new BehaviorEventCreateRequest(
                base.eventId(),
                base.eventType(),
                base.occurredAt(),
                base.externalCustomerRef(),
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                value
        );
    }

    private static FinancialTransaction transaction(
            TransactionType type,
            String customer,
            String sender,
            String recipient
    ) {
        TransactionChannel channel = switch (type) {
            case ACCOUNT_TRANSFER -> TransactionChannel.MOBILE_BANKING;
            case OPEN_BANKING_TRANSFER -> TransactionChannel.OPEN_BANKING;
            case ATM_WITHDRAWAL -> TransactionChannel.ATM;
            case LOAN_DISBURSED -> TransactionChannel.CORE_BANKING;
        };
        return new FinancialTransaction(
                TRANSACTION_ID,
                type,
                BigDecimal.ONE,
                "KRW",
                NOW,
                customer,
                sender,
                recipient,
                channel,
                null
        );
    }

    private void assertFailure(
            BehaviorEventCreateRequest request,
            BehaviorEventValidationType type,
            String field,
            String code
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        BehaviorEventValidationException.class,
                        exception -> {
                            assertThat(exception.getType()).isEqualTo(type);
                            assertThat(exception.getField()).isEqualTo(field);
                            assertThat(exception.getCode()).isEqualTo(code);
                        }
                );
    }
}
