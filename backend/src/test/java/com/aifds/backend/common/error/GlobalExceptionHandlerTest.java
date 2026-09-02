package com.aifds.backend.common.error;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.observability.TransactionIntakeMetricsFilter;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.fraudcase.exception.FraudCaseWorkflowException;
import com.aifds.backend.fraudcase.exception.InvestigationNoteException;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidationException;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidationType;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationException;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationType;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import com.aifds.backend.transaction.exception.TransactionIntakeRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "trace_unit_test_01";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void validationHandlerMarksPublicIntakeAtTheSharedFinishOnceBoundary()
            throws Exception {
        TransactionProcessingMetricsRecorder recorder = mock(
                TransactionProcessingMetricsRecorder.class
        );
        TransactionIntakeMetricsFilter filter =
                new TransactionIntakeMetricsFilter(recorder);
        MockHttpServletRequest request = requestWithTraceId();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/transactions");
        request.setServletPath("/api/v1/transactions");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) ->
                        handler.handleTransactionValidation(
                                new TransactionValidationException(
                                        TransactionValidationType.FORMAT,
                                        "amount",
                                        "INVALID_AMOUNT_FORMAT",
                                        "amount format is invalid"
                                ),
                                request
                        )
        );

        verify(recorder).recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome
                        .VALIDATION_REJECTED
        );
    }

    @Test
    void mapsFormatAndDomainValidationToTheirApprovedStatuses() {
        TransactionValidationException format =
                new TransactionValidationException(
                        TransactionValidationType.FORMAT,
                        "amount",
                        "INVALID_AMOUNT_FORMAT",
                        "amount must be an unsigned decimal integer string"
                );
        TransactionValidationException domain =
                new TransactionValidationException(
                        TransactionValidationType.DOMAIN,
                        "recipientAccountRef",
                        "RECIPIENT_ACCOUNT_REQUIRED",
                        "recipientAccountRef is required for transfer transactions"
                );

        ResponseEntity<ApiErrorResponse> formatResponse =
                handler.handleTransactionValidation(
                        format,
                        requestWithTraceId()
                );
        ResponseEntity<ApiErrorResponse> domainResponse =
                handler.handleTransactionValidation(
                        domain,
                        requestWithTraceId()
                );

        assertThat(formatResponse.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(formatResponse.getBody().code())
                .isEqualTo(GlobalExceptionHandler.VALIDATION_ERROR);
        assertThat(formatResponse.getBody().fieldErrors())
                .containsExactly(new FieldErrorResponse(
                        "amount",
                        "INVALID_AMOUNT_FORMAT",
                        "amount must be an unsigned decimal integer string"
                ));
        assertThat(domainResponse.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(domainResponse.getBody().code())
                .isEqualTo(GlobalExceptionHandler.VALIDATION_ERROR);
        assertThat(formatResponse.getBody().traceId()).isEqualTo(TRACE_ID);
        assertThat(domainResponse.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void mapsNoteValidationStatusAndBusinessConflictsWithoutCauseDetails() {
        ResponseEntity<ApiErrorResponse> validation =
                handler.handleInvestigationNoteValidation(
                        new InvestigationNoteValidationException(
                                InvestigationNoteValidationType.DOMAIN,
                                "content", "CONTENT_TOO_LONG", "content is invalid"
                        ), requestWithTraceId()
                );
        ResponseEntity<ApiErrorResponse> conflict = handler.handleInvestigationNote(
                new InvestigationNoteException(
                        InvestigationNoteException.Reason.NOTE_NOT_ALLOWED,
                        new IllegalStateException("secret content and investigation_note")
                ), requestWithTraceId()
        );

        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().code()).isEqualTo("NOTE_NOT_ALLOWED");
        assertThat(conflict.getBody().toString())
                .doesNotContain("secret", "investigation_note");
        assertThat(conflict.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void mapsStateTransitionWithoutExposingInternalStates() {
        IdempotencyStateTransitionNotAllowedException exception =
                new IdempotencyStateTransitionNotAllowedException(
                        IdempotencyProcessingStatus.COMPLETED,
                        IdempotencyProcessingStatus.IN_PROGRESS
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleStateTransitionNotAllowed(
                        exception,
                        requestWithTraceId()
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code())
                .isEqualTo(GlobalExceptionHandler.STATE_TRANSITION_NOT_ALLOWED);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.STATE_TRANSITION_MESSAGE)
                .doesNotContain("COMPLETED", "IN_PROGRESS");
        assertThat(response.getBody().fieldErrors()).isEmpty();
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void mapsInternalIdempotencyExceptionsToTheSameSafeResponse() {
        ResponseEntity<ApiErrorResponse> missingRecord =
                handler.handleInternalIdempotencyException(
                        new IdempotencyRecordNotFoundException(42L),
                        requestWithTraceId()
                );
        ResponseEntity<ApiErrorResponse> missingTransaction =
                handler.handleInternalIdempotencyException(
                        new IdempotencyCompletionTransactionNotFoundException(
                                UUID.fromString(
                                        "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
                                )
                        ),
                        requestWithTraceId()
                );

        assertSafeInternalError(missingRecord);
        assertSafeInternalError(missingTransaction);
    }

    @Test
    void keepsNullTraceIdWhenRequestAttributeIsAbsentOrRequestIsNull() {
        IdempotencyRecordNotFoundException exception =
                new IdempotencyRecordNotFoundException(42L);

        ResponseEntity<ApiErrorResponse> missingAttribute =
                handler.handleInternalIdempotencyException(
                        exception,
                        new MockHttpServletRequest()
                );
        ResponseEntity<ApiErrorResponse> missingRequest =
                handler.handleInternalIdempotencyException(exception, null);

        assertThat(missingAttribute.getBody().traceId()).isNull();
        assertThat(missingRequest.getBody().traceId()).isNull();
    }

    @Test
    void mapsIntakeDependencyUnavailableToFixedSafe503() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleTransactionIntakeRejected(
                        TransactionIntakeRejectedException
                                .dependencyUnavailable(),
                        requestWithTraceId()
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code())
                .isEqualTo(GlobalExceptionHandler.DEPENDENCY_UNAVAILABLE);
        assertThat(response.getBody().message()).isEqualTo(
                GlobalExceptionHandler
                        .INTAKE_DEPENDENCY_UNAVAILABLE_MESSAGE
        );
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void mapsTypedFailureWithoutExposingInternalContext() {
        TransactionIntakeRejectedException exception =
                TransactionIntakeRejectedException.typedFailure(
                        500,
                        "INTERNAL_ERROR",
                        "요청을 처리하는 중 오류가 발생했습니다."
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleTransactionIntakeRejected(
                        exception,
                        requestWithTraceId()
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("요청을 처리하는 중 오류가 발생했습니다.");
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    @Test
    void rejectsUnapprovedTypedFailureMappingBeforeResponseCreation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                TransactionIntakeRejectedException.typedFailure(
                        503,
                        "PROVIDER_SECRET",
                        "credential=secret"
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential=secret");
    }

    @Test
    void mapsFraudCaseWorkflowConflictsAndDependenciesSafely() {
        assertWorkflow(
                FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT,
                HttpStatus.CONFLICT,
                GlobalExceptionHandler.CASE_STATUS_CONFLICT
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT,
                HttpStatus.CONFLICT,
                GlobalExceptionHandler.CASE_ASSIGNEE_CONFLICT
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.CASE_ALREADY_CLOSED,
                HttpStatus.CONFLICT,
                GlobalExceptionHandler.CASE_ALREADY_CLOSED
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                HttpStatus.CONFLICT,
                GlobalExceptionHandler.CONCURRENT_MODIFICATION
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.ASSIGNEE_REQUIRED,
                HttpStatus.UNPROCESSABLE_ENTITY,
                GlobalExceptionHandler.ASSIGNEE_REQUIRED
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.INCONSISTENT_CASE_DATA,
                HttpStatus.INTERNAL_SERVER_ERROR,
                GlobalExceptionHandler.INTERNAL_ERROR
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.DEPENDENCY_TIMEOUT,
                HttpStatus.SERVICE_UNAVAILABLE,
                GlobalExceptionHandler.DEPENDENCY_TIMEOUT
        );
        assertWorkflow(
                FraudCaseWorkflowException.Reason.DEPENDENCY_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                GlobalExceptionHandler.DEPENDENCY_UNAVAILABLE
        );
    }

    @Test
    void mapsInvalidAssigneeToDedicated422WithoutInputReflection() {
        FraudCaseValidationException exception =
                new FraudCaseValidationException(
                        FraudCaseValidationType.DOMAIN,
                        "assigneeRef",
                        "INVALID_ASSIGNEE_REF",
                        "assigneeRef must be a canonical lowercase UUID v4"
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleFraudCaseValidation(
                        exception,
                        requestWithTraceId()
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code())
                .isEqualTo(GlobalExceptionHandler.INVALID_ASSIGNEE_REF);
        assertThat(response.getBody().message())
                .isEqualTo(
                        GlobalExceptionHandler.INVALID_ASSIGNEE_REF_MESSAGE
                );
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void mapsMissingFinalDispositionToDedicatedSafe422() {
        FraudCaseValidationException exception =
                new FraudCaseValidationException(
                        FraudCaseValidationType.DOMAIN,
                        "finalDisposition",
                        "FINAL_DISPOSITION_REQUIRED",
                        "finalDisposition is required"
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleFraudCaseValidation(
                        exception,
                        requestWithTraceId()
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code())
                .isEqualTo(GlobalExceptionHandler.FINAL_DISPOSITION_REQUIRED);
        assertThat(response.getBody().message())
                .doesNotContain("finalDisposition", "credential", "SELECT");
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    private void assertWorkflow(
            FraudCaseWorkflowException.Reason reason,
            HttpStatus status,
            String code
    ) {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleFraudCaseWorkflow(
                        new FraudCaseWorkflowException(
                                reason,
                                new IllegalStateException(
                                        "SELECT password FROM credential"
                                )
                        ),
                        requestWithTraceId()
                );
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).doesNotContain(
                "SELECT", "password", "credential", "IllegalStateException"
        );
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
    }

    private void assertSafeInternalError(
            ResponseEntity<ApiErrorResponse> response
    ) {
        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code())
                .isEqualTo(GlobalExceptionHandler.INTERNAL_ERROR);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE)
                .doesNotContain("42", "2f4c0a4e", "Idempotency");
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    private MockHttpServletRequest requestWithTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE,
                TRACE_ID
        );
        return request;
    }
}
