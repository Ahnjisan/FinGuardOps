package com.aifds.backend.common.error;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import com.aifds.backend.transaction.exception.TransactionIntakeRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "trace_unit_test_01";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
