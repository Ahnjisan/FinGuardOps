package com.aifds.backend.transaction.validation;

import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static com.aifds.backend.transaction.validation.TransactionRequestValidator.AMOUNT_NOT_POSITIVE;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.AMOUNT_OUT_OF_RANGE;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.INVALID_AMOUNT_FORMAT;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.INVALID_OCCURRED_AT_FORMAT;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.INVALID_REFERENCE_LENGTH;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.INVALID_UUID_FORMAT;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.INVALID_UUID_VARIANT;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.INVALID_UUID_VERSION;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.OCCURRED_AT_TOO_FAR_IN_FUTURE;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.RECIPIENT_ACCOUNT_FORBIDDEN;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.RECIPIENT_ACCOUNT_REQUIRED;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.REFERENCE_HAS_SURROUNDING_WHITESPACE;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.REQUIRED_FIELD;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.TRANSACTION_CHANNEL_MISMATCH;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.UNSUPPORTED_CURRENCY;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.UNSUPPORTED_TRANSACTION_CHANNEL;
import static com.aifds.backend.transaction.validation.TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionRequestValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T01:10:30Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Validator BEAN_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final TransactionRequestValidator validator =
            new TransactionRequestValidator(FIXED_CLOCK, BEAN_VALIDATOR);

    @Test
    void returnsStronglyTypedCommandOnlyAfterValidationSucceeds() {
        ValidatedTransactionCommand command = validator.validate(baseRequest());

        assertThat(command.transactionId())
                .isEqualTo(UUID.fromString(
                        "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
                ));
        assertThat(command.transactionType())
                .isEqualTo(TransactionType.ACCOUNT_TRANSFER);
        assertThat(command.amount()).isEqualByComparingTo("1250000");
        assertThat(command.occurredAt())
                .isEqualTo(Instant.parse("2026-07-23T01:10:00Z"));
        assertThat(command.channel())
                .isEqualTo(TransactionChannel.MOBILE_BANKING);
    }

    @Test
    void createsFingerprintInputWithExactlyTheValidatedCommandValues() {
        ValidatedTransactionCommand command = validator.validate(baseRequest());

        TransactionFingerprintInput input = command.toFingerprintInput();

        assertThat(input.transactionId()).isEqualTo(command.transactionId());
        assertThat(input.transactionType()).isEqualTo(command.transactionType());
        assertThat(input.amount()).isEqualByComparingTo(command.amount());
        assertThat(input.currencyCode()).isEqualTo(command.currencyCode());
        assertThat(input.occurredAt()).isEqualTo(command.occurredAt());
        assertThat(input.externalCustomerRef())
                .isEqualTo(command.externalCustomerRef());
        assertThat(input.senderAccountRef())
                .isEqualTo(command.senderAccountRef());
        assertThat(input.recipientAccountRef())
                .isEqualTo(command.recipientAccountRef());
        assertThat(input.channel()).isEqualTo(command.channel());
        assertThat(input.deviceRef()).isEqualTo(command.deviceRef());
    }

    @Test
    void acceptsCanonicalUppercaseUuidWithoutAddingLowercaseInputRule() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001",
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                baseRequest().channel(),
                baseRequest().deviceRef()
        );

        assertThat(validator.validate(request).transactionId())
                .isEqualTo(baseUuid());
    }

    @Test
    void rejectsNonCanonicalAbbreviatedUuidBeforeCallingUuidParserContract() {
        TransactionCreateRequest request = withTransactionId("1-1-1-1-1");

        assertFormatFailure(request, "transactionId", INVALID_UUID_FORMAT);
    }

    @Test
    void rejectsUuidWithNonVersionFourValue() {
        TransactionCreateRequest request = withTransactionId(
                "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"
        );

        assertFormatFailure(request, "transactionId", INVALID_UUID_VERSION);
    }

    @Test
    void rejectsUuidWithNonRfc4122Variant() {
        TransactionCreateRequest request = withTransactionId(
                "2f4c0a4e-8a9d-4c2f-1a1b-7d6e5f430001"
        );

        assertFormatFailure(request, "transactionId", INVALID_UUID_VARIANT);
    }

    @Test
    void rejectsMissingAndExplicitNullRequiredFieldsAsFormatErrors() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                null,
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                baseRequest().channel(),
                baseRequest().deviceRef()
        );

        assertFormatFailure(request, "amount", REQUIRED_FIELD);
    }

    @Test
    void rejectsUnsupportedTransactionTypeAndChannelAsFormatErrors() {
        TransactionCreateRequest invalidType = copy(
                baseRequest(),
                baseRequest().transactionId(),
                "account_transfer",
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                baseRequest().channel(),
                baseRequest().deviceRef()
        );
        TransactionCreateRequest invalidChannel = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                "mobile_banking",
                baseRequest().deviceRef()
        );

        assertFormatFailure(
                invalidType,
                "transactionType",
                UNSUPPORTED_TRANSACTION_TYPE
        );
        assertFormatFailure(
                invalidChannel,
                "channel",
                UNSUPPORTED_TRANSACTION_CHANNEL
        );
    }

    @Test
    void acceptsMaximumAmount() {
        ValidatedTransactionCommand command = validator.validate(
                withAmount("999999999999999")
        );

        assertThat(command.amount())
                .isEqualByComparingTo("999999999999999");
    }

    @Test
    void acceptsLeadingZeroesAndUsesExistingFingerprintNormalization() {
        ValidatedTransactionCommand command = validator.validate(withAmount("0001"));

        assertThat(command.amount()).isEqualByComparingTo(BigDecimal.ONE);
        String normalized = new TransactionRequestFingerprint(new ObjectMapper())
                .normalize(command.toFingerprintInput());
        assertThat(normalized).contains("\"amount\":\"1\"");
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void rejectsInvalidAmounts(String amount, String expectedCode) {
        assertFormatFailure(withAmount(amount), "amount", expectedCode);
    }

    @Test
    void acceptsOnlyExactKrwCurrencyCode() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                "krw",
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                baseRequest().channel(),
                baseRequest().deviceRef()
        );

        assertFormatFailure(request, "currencyCode", UNSUPPORTED_CURRENCY);
    }

    @Test
    void acceptsExactlyFiveMinutesInTheFuture() {
        ValidatedTransactionCommand command = validator.validate(
                withOccurredAt(NOW.plusSeconds(300).toString())
        );

        assertThat(command.occurredAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void rejectsMoreThanFiveMinutesInTheFuture() {
        assertFormatFailure(
                withOccurredAt(NOW.plusSeconds(301).toString()),
                "occurredAt",
                OCCURRED_AT_TOO_FAR_IN_FUTURE
        );
    }

    @Test
    void rejectsUtcOffsetAndInvalidInstantInsteadOfCorrectingThem() {
        assertFormatFailure(
                withOccurredAt("2026-07-23T01:10:00+00:00"),
                "occurredAt",
                INVALID_OCCURRED_AT_FORMAT
        );
        assertFormatFailure(
                withOccurredAt("2026-07-23T99:10:00Z"),
                "occurredAt",
                INVALID_OCCURRED_AT_FORMAT
        );
    }

    @Test
    void readsClockInstantExactlyOncePerValidation() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        TransactionRequestValidator clockValidator =
                new TransactionRequestValidator(clock, BEAN_VALIDATOR);

        clockValidator.validate(baseRequest());

        verify(clock, times(1)).instant();
    }

    @Test
    void acceptsOneAndOneHundredTwentyEightCharacterReferences() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                "a",
                "s".repeat(128),
                "r",
                baseRequest().channel(),
                "d".repeat(128)
        );

        assertThat(validator.validate(request).externalCustomerRef())
                .isEqualTo("a");
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void rejectsInvalidReferenceWithoutChangingIt(
            String reference,
            String expectedCode
    ) {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                reference,
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                baseRequest().channel(),
                baseRequest().deviceRef()
        );

        assertFormatFailure(request, "externalCustomerRef", expectedCode);
    }

    @Test
    void rejectsInvalidOptionalReferenceAsFormatError() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                baseRequest().channel(),
                ""
        );

        assertFormatFailure(request, "deviceRef", INVALID_REFERENCE_LENGTH);
    }

    @ParameterizedTest
    @MethodSource("validDomainCombinations")
    void acceptsAllFourTransactionDomainCombinations(
            String transactionType,
            String recipientAccountRef,
            String channel
    ) {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                transactionType,
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                recipientAccountRef,
                channel,
                baseRequest().deviceRef()
        );

        assertThat(validator.validate(request).transactionType().name())
                .isEqualTo(transactionType);
    }

    @Test
    void treatsMissingTransferRecipientAsDomainError() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                null,
                baseRequest().channel(),
                baseRequest().deviceRef()
        );

        assertDomainFailure(
                request,
                "recipientAccountRef",
                RECIPIENT_ACCOUNT_REQUIRED
        );
    }

    @Test
    void treatsForbiddenAtmRecipientAsDomainError() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                "ATM_WITHDRAWAL",
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                "recipient",
                "ATM",
                baseRequest().deviceRef()
        );

        assertDomainFailure(
                request,
                "recipientAccountRef",
                RECIPIENT_ACCOUNT_FORBIDDEN
        );
    }

    @Test
    void treatsTransactionChannelMismatchAsDomainError() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                baseRequest().recipientAccountRef(),
                "OPEN_BANKING",
                baseRequest().deviceRef()
        );

        assertDomainFailure(request, "channel", TRANSACTION_CHANNEL_MISMATCH);
    }

    @Test
    void completesAllFormatValidationBeforeDomainValidation() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                baseRequest().transactionType(),
                "1.5",
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                null,
                "OPEN_BANKING",
                baseRequest().deviceRef()
        );

        assertFormatFailure(request, "amount", INVALID_AMOUNT_FORMAT);
    }

    @Test
    void normalizesMissingAndExplicitOptionalNullsToCommandNulls() {
        TransactionCreateRequest request = copy(
                baseRequest(),
                baseRequest().transactionId(),
                "ATM_WITHDRAWAL",
                baseRequest().amount(),
                baseRequest().currencyCode(),
                baseRequest().occurredAt(),
                baseRequest().externalCustomerRef(),
                baseRequest().senderAccountRef(),
                null,
                "ATM",
                null
        );

        ValidatedTransactionCommand command = validator.validate(request);

        assertThat(command.recipientAccountRef()).isNull();
        assertThat(command.deviceRef()).isNull();
        assertThat(command.toFingerprintInput().recipientAccountRef()).isNull();
        assertThat(command.toFingerprintInput().deviceRef()).isNull();
    }

    private void assertFormatFailure(
            TransactionCreateRequest request,
            String expectedField,
            String expectedCode
    ) {
        assertValidationFailure(
                request,
                TransactionValidationType.FORMAT,
                expectedField,
                expectedCode
        );
    }

    private void assertDomainFailure(
            TransactionCreateRequest request,
            String expectedField,
            String expectedCode
    ) {
        assertValidationFailure(
                request,
                TransactionValidationType.DOMAIN,
                expectedField,
                expectedCode
        );
    }

    private void assertValidationFailure(
            TransactionCreateRequest request,
            TransactionValidationType expectedType,
            String expectedField,
            String expectedCode
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType()).isEqualTo(expectedType);
                            assertThat(exception.getField()).isEqualTo(expectedField);
                            assertThat(exception.getCode()).isEqualTo(expectedCode);
                        }
                );
    }

    private TransactionCreateRequest baseRequest() {
        return new TransactionCreateRequest(
                baseUuid().toString(),
                "ACCOUNT_TRANSFER",
                "1250000",
                "KRW",
                "2026-07-23T01:10:00Z",
                "cust_ref_demo_a7f2",
                "acct_ref_demo_s91c",
                "acct_ref_demo_r44d",
                "MOBILE_BANKING",
                "device_ref_demo_18b3"
        );
    }

    private UUID baseUuid() {
        return UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001");
    }

    private TransactionCreateRequest withTransactionId(String transactionId) {
        TransactionCreateRequest base = baseRequest();
        return copy(
                base,
                transactionId,
                base.transactionType(),
                base.amount(),
                base.currencyCode(),
                base.occurredAt(),
                base.externalCustomerRef(),
                base.senderAccountRef(),
                base.recipientAccountRef(),
                base.channel(),
                base.deviceRef()
        );
    }

    private TransactionCreateRequest withAmount(String amount) {
        TransactionCreateRequest base = baseRequest();
        return copy(
                base,
                base.transactionId(),
                base.transactionType(),
                amount,
                base.currencyCode(),
                base.occurredAt(),
                base.externalCustomerRef(),
                base.senderAccountRef(),
                base.recipientAccountRef(),
                base.channel(),
                base.deviceRef()
        );
    }

    private TransactionCreateRequest withOccurredAt(String occurredAt) {
        TransactionCreateRequest base = baseRequest();
        return copy(
                base,
                base.transactionId(),
                base.transactionType(),
                base.amount(),
                base.currencyCode(),
                occurredAt,
                base.externalCustomerRef(),
                base.senderAccountRef(),
                base.recipientAccountRef(),
                base.channel(),
                base.deviceRef()
        );
    }

    private TransactionCreateRequest copy(
            TransactionCreateRequest ignored,
            String transactionId,
            String transactionType,
            String amount,
            String currencyCode,
            String occurredAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            String channel,
            String deviceRef
    ) {
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

    private static Stream<Arguments> invalidAmounts() {
        return Stream.of(
                Arguments.of("0", AMOUNT_NOT_POSITIVE),
                Arguments.of("+1", INVALID_AMOUNT_FORMAT),
                Arguments.of("-1", INVALID_AMOUNT_FORMAT),
                Arguments.of("1.0", INVALID_AMOUNT_FORMAT),
                Arguments.of("1e3", INVALID_AMOUNT_FORMAT),
                Arguments.of("9999999999999999", AMOUNT_OUT_OF_RANGE)
        );
    }

    private static Stream<Arguments> invalidReferences() {
        return Stream.of(
                Arguments.of("", INVALID_REFERENCE_LENGTH),
                Arguments.of("a".repeat(129), INVALID_REFERENCE_LENGTH),
                Arguments.of(" leading", REFERENCE_HAS_SURROUNDING_WHITESPACE),
                Arguments.of("trailing ", REFERENCE_HAS_SURROUNDING_WHITESPACE)
        );
    }

    private static Stream<Arguments> validDomainCombinations() {
        return Stream.of(
                Arguments.of(
                        "ACCOUNT_TRANSFER",
                        "recipient",
                        "MOBILE_BANKING"
                ),
                Arguments.of(
                        "OPEN_BANKING_TRANSFER",
                        "recipient",
                        "OPEN_BANKING"
                ),
                Arguments.of("ATM_WITHDRAWAL", null, "ATM"),
                Arguments.of("LOAN_DISBURSED", null, "CORE_BANKING")
        );
    }
}
