package com.aifds.backend.transaction.dto;

import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.stream.Stream;

import static com.aifds.backend.transaction.dto.TransactionCreateRequestDeserializer.DUPLICATE_JSON_FIELD;
import static com.aifds.backend.transaction.dto.TransactionCreateRequestDeserializer.INVALID_JSON_ROOT;
import static com.aifds.backend.transaction.dto.TransactionCreateRequestDeserializer.INVALID_JSON_TOKEN;
import static com.aifds.backend.transaction.dto.TransactionCreateRequestDeserializer.UNKNOWN_JSON_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@JsonTest
class TransactionCreateRequestJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void deserializesOnlyTheAllowedRequestFieldsWithoutChangingValues()
            throws Exception {
        TransactionCreateRequest request = objectMapper.readValue(
                completeJson(),
                TransactionCreateRequest.class
        );

        assertThat(request).isEqualTo(new TransactionCreateRequest(
                "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001",
                "ACCOUNT_TRANSFER",
                "0001",
                "KRW",
                "2026-07-23T01:15:30Z",
                "cust_ref_demo_a7f2",
                "acct_ref_demo_s91c",
                "acct_ref_demo_r44d",
                "MOBILE_BANKING",
                "device_ref_demo_18b3"
        ));
    }

    @Test
    void normalizesMissingOptionalFieldsToNull() throws Exception {
        TransactionCreateRequest request = objectMapper.readValue(
                requiredFieldsOnlyJson(),
                TransactionCreateRequest.class
        );

        assertThat(request.recipientAccountRef()).isNull();
        assertThat(request.deviceRef()).isNull();
    }

    @Test
    void keepsExplicitOptionalNullsAsNull() throws Exception {
        String json = requiredFieldsOnlyJson().replace(
                "\"channel\": \"ATM\"",
                "\"recipientAccountRef\": null,\n"
                        + "  \"channel\": \"ATM\",\n"
                        + "  \"deviceRef\": null"
        );

        TransactionCreateRequest request = objectMapper.readValue(
                json,
                TransactionCreateRequest.class
        );

        assertThat(request.recipientAccountRef()).isNull();
        assertThat(request.deviceRef()).isNull();
    }

    @Test
    void leavesMissingRequiredFieldAsNullForFormatValidation() throws Exception {
        String json = requiredFieldsOnlyJson().replace(
                "  \"transactionId\": "
                        + "\"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001\",\n",
                ""
        );

        TransactionCreateRequest request = objectMapper.readValue(
                json,
                TransactionCreateRequest.class
        );

        assertThat(request.transactionId()).isNull();
    }

    @ParameterizedTest
    @MethodSource("nonObjectRoots")
    void rejectsNonObjectJsonRootAndPreservesFormatCause(String json) {
        assertFormatFailure(json, "$", INVALID_JSON_ROOT);
    }

    @Test
    void rejectsUnknownFieldAndPreservesFieldAndCode() {
        String json = completeJson().replace(
                "\"deviceRef\": \"device_ref_demo_18b3\"",
                "\"deviceRef\": \"device_ref_demo_18b3\",\n"
                        + "  \"unexpected\": \"value\""
        );

        assertFormatFailure(json, "unexpected", UNKNOWN_JSON_FIELD);
    }

    @Test
    void rejectsDuplicateJsonKeyBeforeAnyValueCanBeOverwritten() {
        String json = completeJson().replace(
                "\"amount\": \"0001\"",
                "\"amount\": \"0001\",\n  \"amount\": \"2\""
        );

        assertFormatFailure(json, "amount", DUPLICATE_JSON_FIELD);
    }

    @ParameterizedTest
    @MethodSource("invalidStringTokens")
    void rejectsScalarCoercionAndNonStringTokens(
            String json,
            String expectedField
    ) {
        assertFormatFailure(json, expectedField, INVALID_JSON_TOKEN);
    }

    private void assertFormatFailure(
            String json,
            String expectedField,
            String expectedCode
    ) {
        Throwable thrown = catchThrowable(() -> objectMapper.readValue(
                json,
                TransactionCreateRequest.class
        ));

        assertThat(thrown).isNotNull();
        TransactionValidationException validationException =
                findValidationException(thrown);
        assertThat(validationException)
                .as("Jackson cause chain must preserve transaction validation details")
                .isNotNull();
        assertThat(validationException.getType())
                .isEqualTo(TransactionValidationType.FORMAT);
        assertThat(validationException.getField()).isEqualTo(expectedField);
        assertThat(validationException.getCode()).isEqualTo(expectedCode);
    }

    private TransactionValidationException findValidationException(
            Throwable throwable
    ) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TransactionValidationException validationException) {
                return validationException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Stream<String> nonObjectRoots() {
        return Stream.of(
                "[]",
                "\"request\"",
                "123",
                "true",
                "null"
        );
    }

    private static Stream<Arguments> invalidStringTokens() {
        return Stream.of(
                Arguments.of(completeJson().replace(
                        "\"amount\": \"0001\"",
                        "\"amount\": 1"
                ), "amount"),
                Arguments.of(completeJson().replace(
                        "\"transactionType\": \"ACCOUNT_TRANSFER\"",
                        "\"transactionType\": true"
                ), "transactionType"),
                Arguments.of(completeJson().replace(
                        "\"deviceRef\": \"device_ref_demo_18b3\"",
                        "\"deviceRef\": []"
                ), "deviceRef"),
                Arguments.of(completeJson().replace(
                        "\"recipientAccountRef\": \"acct_ref_demo_r44d\"",
                        "\"recipientAccountRef\": {}"
                ), "recipientAccountRef")
        );
    }

    private static String completeJson() {
        return """
                {
                  "transactionId": "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001",
                  "transactionType": "ACCOUNT_TRANSFER",
                  "amount": "0001",
                  "currencyCode": "KRW",
                  "occurredAt": "2026-07-23T01:15:30Z",
                  "externalCustomerRef": "cust_ref_demo_a7f2",
                  "senderAccountRef": "acct_ref_demo_s91c",
                  "recipientAccountRef": "acct_ref_demo_r44d",
                  "channel": "MOBILE_BANKING",
                  "deviceRef": "device_ref_demo_18b3"
                }
                """;
    }

    private static String requiredFieldsOnlyJson() {
        return """
                {
                  "transactionId": "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
                  "transactionType": "ATM_WITHDRAWAL",
                  "amount": "1000",
                  "currencyCode": "KRW",
                  "occurredAt": "2026-07-23T01:15:30Z",
                  "externalCustomerRef": "customer",
                  "senderAccountRef": "sender",
                  "channel": "ATM"
                }
                """;
    }
}
