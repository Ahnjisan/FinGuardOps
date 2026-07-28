package com.aifds.backend.transaction.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.aifds.backend.transaction.validation.IdempotencyKeyValidator.IDEMPOTENCY_KEY_INVALID_CHARACTERS;
import static com.aifds.backend.transaction.validation.IdempotencyKeyValidator.IDEMPOTENCY_KEY_INVALID_LENGTH;
import static com.aifds.backend.transaction.validation.IdempotencyKeyValidator.IDEMPOTENCY_KEY_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyValidatorTest {

    private final IdempotencyKeyValidator validator =
            new IdempotencyKeyValidator();

    @Test
    void acceptsEightAndOneHundredTwentyEightCharacters() {
        assertThat(validator.validate("Abc._:-1")).isEqualTo("Abc._:-1");

        String maximumLengthKey = "a".repeat(128);
        assertThat(validator.validate(maximumLengthKey))
                .isEqualTo(maximumLengthKey);
    }

    @Test
    void preservesTheOriginalKeyWithoutTrimmingOrChangingCase() {
        String key = "AbC_1234";

        assertThat(validator.validate(key)).isSameAs(key);
    }

    @Test
    void rejectsMissingKey() {
        assertFormatFailure(null, IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void rejectsSevenAndOneHundredTwentyNineCharacters() {
        assertFormatFailure("a".repeat(7), IDEMPOTENCY_KEY_INVALID_LENGTH);
        assertFormatFailure("a".repeat(129), IDEMPOTENCY_KEY_INVALID_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcd 123",
            "한글키123456",
            "abcd/123",
            "abcd\\123"
    })
    void rejectsUnsupportedCharacters(String key) {
        assertFormatFailure(key, IDEMPOTENCY_KEY_INVALID_CHARACTERS);
    }

    private void assertFormatFailure(String key, String expectedCode) {
        assertThatThrownBy(() -> validator.validate(key))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType())
                                    .isEqualTo(TransactionValidationType.FORMAT);
                            assertThat(exception.getField())
                                    .isEqualTo("Idempotency-Key");
                            assertThat(exception.getCode()).isEqualTo(expectedCode);
                        }
                );
    }
}
