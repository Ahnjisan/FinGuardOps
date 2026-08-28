package com.aifds.backend.common.error;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.observability.TransactionIntakeMetricsFilter;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.exception.TransactionQueryUnavailableException;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerMockMvcTest.TestController.class)
@Import({
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        TransactionIntakeMetricsFilter.class,
        GlobalExceptionHandlerMockMvcTest.TestController.class
})
class GlobalExceptionHandlerMockMvcTest {

    private static final String MALFORMED_TRACE_ID = "trace_malformed_01";
    private static final String BEAN_VALIDATION_TRACE_ID =
            "trace_bean_validation_01";
    private static final String DOMAIN_TRACE_ID = "trace_domain_01";
    private static final String INTERNAL_TRACE_ID = "trace_internal_01";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionProcessingMetricsRecorder metricsRecorder;

    @Test
    void malformedJsonReturnsValidationErrorWithoutFieldErrors() throws Exception {
        mockMvc.perform(post("/test/errors/json")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                MALFORMED_TRACE_ID
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.VALIDATION_MESSAGE))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        MALFORMED_TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(MALFORMED_TRACE_ID))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        verifyNoInteractions(metricsRecorder);
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
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
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
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                BEAN_VALIDATION_TRACE_ID
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        BEAN_VALIDATION_TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId")
                        .value(BEAN_VALIDATION_TRACE_ID))
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
        String traceId = "trace_state_transition_01";
        String response = mockMvc.perform(get("/test/errors/state-transition")
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.code")
                        .value("STATE_TRANSITION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.STATE_TRANSITION_MESSAGE))
                .andExpect(jsonPath("$.traceId").value(traceId))
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
    @ExtendWith(OutputCaptureExtension.class)
    void responseContainsNoUnapprovedContractFieldsOrRejectedValues(
            CapturedOutput output
    ) throws Exception {
        String querySecret = "query_secret_must_not_be_logged";
        String response = mockMvc.perform(get("/test/errors/unexpected")
                        .queryParam("debug", querySecret)
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                INTERNAL_TRACE_ID
                        ))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        INTERNAL_TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE))
                .andExpect(jsonPath("$.traceId").value(INTERNAL_TRACE_ID))
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
        assertThat(output)
                .contains("ERROR")
                .contains("Internal server error [traceId="
                        + INTERNAL_TRACE_ID
                        + ", method=GET, path=/test/errors/unexpected]")
                .contains("SensitiveFailureClass: SELECT secret FROM account")
                .contains("at " + TestController.class.getName()
                        + ".unexpected")
                .doesNotContain(querySecret);
        assertThat(output.toString())
                .containsOnlyOnce("Internal server error [traceId=");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void existingInternalHandlerUsesTheSameCommonLoggingPath(
            CapturedOutput output
    ) throws Exception {
        mockMvc.perform(get("/test/errors/missing-idempotency-record")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                INTERNAL_TRACE_ID
                        ))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output)
                .contains("Internal server error [traceId="
                        + INTERNAL_TRACE_ID
                        + ", method=GET, path=/test/errors/missing-idempotency-record]")
                .contains(IdempotencyRecordNotFoundException.class.getName());
        assertThat(output.toString())
                .containsOnlyOnce("Internal server error [traceId=");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void clientAndDependencyErrorsDoNotWriteInternalErrorLogs(
            CapturedOutput output
    ) throws Exception {
        mockMvc.perform(get("/test/errors/domain"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/test/errors/dependency-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("DEPENDENCY_UNAVAILABLE"));

        assertThat(output)
                .doesNotContain("Internal server error [traceId=");
    }

    @Test
    void domainValidationUsesTheSameTraceIdInHeaderAndBody()
            throws Exception {
        mockMvc.perform(get("/test/errors/domain")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                DOMAIN_TRACE_ID
                        ))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        DOMAIN_TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(DOMAIN_TRACE_ID))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.VALIDATION_MESSAGE))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("RECIPIENT_ACCOUNT_REQUIRED"));
    }

    @Test
    void requestCompletionRemovesTraceIdFromTheTestThread() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                INTERNAL_TRACE_ID
                        ))
                .andExpect(status().isInternalServerError());

        assertThat(org.slf4j.MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY))
                .isNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsValidatedTraceIdWithoutExposingInvalidExternalValue(
            CapturedOutput output
    ) throws Exception {
        String validTraceId = "trace_log_valid_01";
        String invalidTraceId = "invalid trace value";

        mockMvc.perform(get("/test/errors/log")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                validTraceId
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        validTraceId
                ));

        MvcResult invalidResult = mockMvc.perform(get("/test/errors/log")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                invalidTraceId
                        ))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                .andReturn();
        String generatedTraceId = invalidResult.getResponse().getHeader(
                TraceIdFilter.TRACE_ID_HEADER
        );

        assertThat(output)
                .contains("[traceId=" + validTraceId + "]")
                .contains("[traceId=" + generatedTraceId + "]")
                .doesNotContain(invalidTraceId);
    }

    private void assertSafeInternalError(String path) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                INTERNAL_TRACE_ID
                        ))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        INTERNAL_TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE))
                .andExpect(jsonPath("$.traceId").value(INTERNAL_TRACE_ID))
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

        private static final Logger LOGGER =
                LoggerFactory.getLogger(TestController.class);

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

        @GetMapping("/dependency-unavailable")
        void dependencyUnavailable() {
            throw new TransactionQueryUnavailableException(
                    new IllegalStateException("repository unavailable")
            );
        }

        @GetMapping("/log")
        void log() {
            LOGGER.info("trace logging probe");
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
