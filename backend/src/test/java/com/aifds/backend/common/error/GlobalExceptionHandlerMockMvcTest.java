package com.aifds.backend.common.error;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerMockMvcTest.TestController.class)
@Import({
        GlobalExceptionHandler.class,
        GlobalExceptionHandlerMockMvcTest.TestController.class
})
class GlobalExceptionHandlerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void malformedJsonReturnsValidationErrorWithoutFieldErrors() throws Exception {
        mockMvc.perform(post("/test/errors/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.VALIDATION_MESSAGE))
                .andExpect(jsonPath("$.traceId").value((Object) null))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void transactionValidationCauseInsideUnreadableMessageKeepsApprovedDetails()
            throws Exception {
        mockMvc.perform(post("/test/errors/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transactionId": 123
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("transactionId"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("INVALID_JSON_TOKEN"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("Transaction request field must be a string or null"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1));
    }

    @Test
    void formatAndDomainValidationUseDifferentApprovedStatuses()
            throws Exception {
        mockMvc.perform(get("/test/errors/format"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("INVALID_AMOUNT_FORMAT"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("amount format is invalid"));

        mockMvc.perform(get("/test/errors/domain"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("RECIPIENT_ACCOUNT_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("recipientAccountRef is required"));
    }

    @Test
    void beanValidationMapsOnlyNotNullAndSortsAndDeduplicatesErrors()
            throws Exception {
        mockMvc.perform(post("/test/errors/bean-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("alpha"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("REQUIRED_FIELD"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("alpha is required"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("beta"))
                .andExpect(jsonPath("$.fieldErrors[1].code")
                        .value("REQUIRED_FIELD"))
                .andExpect(jsonPath("$.fieldErrors[1].reason")
                        .value("beta is required"));
    }

    @Test
    void unknownBeanValidationConstraintDoesNotCreateAnUnapprovedFieldCode()
            throws Exception {
        mockMvc.perform(post("/test/errors/bean-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alpha": "approved",
                                  "beta": "approved",
                                  "gamma": "x"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.fieldErrors[0].code").doesNotExist());
    }

    @Test
    void missingIdempotencyHeaderUsesItsApprovedFieldError() throws Exception {
        mockMvc.perform(get("/test/errors/missing-idempotency-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("Idempotency-Key"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("Idempotency-Key is required"));
    }

    @Test
    void missingOtherHeaderIsNotMisclassifiedAsIdempotencyError()
            throws Exception {
        mockMvc.perform(get("/test/errors/missing-other-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void stateTransitionReturnsSafeConflictWithoutInternalStateDetails()
            throws Exception {
        String response = mockMvc.perform(get("/test/errors/state-transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("STATE_TRANSITION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.STATE_TRANSITION_MESSAGE))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("COMPLETED", "IN_PROGRESS");
    }

    @Test
    void internalIdempotencyAndUnexpectedErrorsUseSafeInternalResponse()
            throws Exception {
        assertSafeInternalError("/test/errors/missing-idempotency-record");
        assertSafeInternalError("/test/errors/missing-completion-transaction");
        assertSafeInternalError("/test/errors/unexpected");
    }

    @Test
    void responseContainsNoUnapprovedContractFieldsOrRejectedValues()
            throws Exception {
        String response = mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE))
                .andExpect(jsonPath("$.traceId").value((Object) null))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.resource").doesNotExist())
                .andExpect(jsonPath("$.rejectedValue").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(
                "SELECT secret FROM account",
                "SensitiveFailureClass",
                "acct_rejected_value",
                "42",
                "2f4c0a4e"
        );
    }

    private void assertSafeInternalError(String path) throws Exception {
        String response = mockMvc.perform(get(path))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE))
                .andExpect(jsonPath("$.traceId").value((Object) null))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(
                "Idempotency",
                "42",
                "2f4c0a4e",
                "SELECT secret FROM account",
                "SensitiveFailureClass",
                "acct_rejected_value"
        );
    }

    @RestController
    @RequestMapping("/test/errors")
    public static class TestController {

        @PostMapping("/json")
        void json(@RequestBody TransactionCreateRequest request) {
        }

        @PostMapping("/bean-validation")
        void beanValidation(
                @Valid @RequestBody BeanValidationRequest request
        ) {
        }

        @GetMapping("/missing-idempotency-header")
        void missingIdempotencyHeader(
                @RequestHeader("Idempotency-Key") String idempotencyKey
        ) {
        }

        @GetMapping("/missing-other-header")
        void missingOtherHeader(
                @RequestHeader("X-Other-Required") String otherHeader
        ) {
        }

        @GetMapping("/format")
        void format() {
            throw new TransactionValidationException(
                    TransactionValidationType.FORMAT,
                    "amount",
                    "INVALID_AMOUNT_FORMAT",
                    "amount format is invalid"
            );
        }

        @GetMapping("/domain")
        void domain() {
            throw new TransactionValidationException(
                    TransactionValidationType.DOMAIN,
                    "recipientAccountRef",
                    "RECIPIENT_ACCOUNT_REQUIRED",
                    "recipientAccountRef is required"
            );
        }

        @GetMapping("/state-transition")
        void stateTransition() {
            throw new IdempotencyStateTransitionNotAllowedException(
                    IdempotencyProcessingStatus.COMPLETED,
                    IdempotencyProcessingStatus.IN_PROGRESS
            );
        }

        @GetMapping("/missing-idempotency-record")
        void missingIdempotencyRecord() {
            throw new IdempotencyRecordNotFoundException(42L);
        }

        @GetMapping("/missing-completion-transaction")
        void missingCompletionTransaction() {
            throw new IdempotencyCompletionTransactionNotFoundException(
                    UUID.fromString(
                            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
                    )
            );
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new SensitiveFailureClass(
                    "SELECT secret FROM account WHERE ref = acct_rejected_value"
            );
        }
    }

    public static class BeanValidationRequest {

        @NotNull
        private String beta;

        @NotNull
        private String alpha;

        @Size(min = 3)
        private String gamma;

        public String getBeta() {
            return beta;
        }

        public void setBeta(String beta) {
            this.beta = beta;
        }

        @NotNull
        public String getAlpha() {
            return alpha;
        }

        public void setAlpha(String alpha) {
            this.alpha = alpha;
        }

        public String getGamma() {
            return gamma;
        }

        public void setGamma(String gamma) {
            this.gamma = gamma;
        }
    }

    static class SensitiveFailureClass extends RuntimeException {

        SensitiveFailureClass(String message) {
            super(message);
        }
    }
}
