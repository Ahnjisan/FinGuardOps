package com.aifds.backend.common.error;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

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
                handler.handleTransactionValidation(format);
        ResponseEntity<ApiErrorResponse> domainResponse =
                handler.handleTransactionValidation(domain);

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
    }

    @Test
    void mapsStateTransitionWithoutExposingInternalStates() {
        IdempotencyStateTransitionNotAllowedException exception =
                new IdempotencyStateTransitionNotAllowedException(
                        IdempotencyProcessingStatus.COMPLETED,
                        IdempotencyProcessingStatus.IN_PROGRESS
                );

        ResponseEntity<ApiErrorResponse> response =
                handler.handleStateTransitionNotAllowed(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code())
                .isEqualTo(GlobalExceptionHandler.STATE_TRANSITION_NOT_ALLOWED);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.STATE_TRANSITION_MESSAGE)
                .doesNotContain("COMPLETED", "IN_PROGRESS");
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    @Test
    void mapsInternalIdempotencyExceptionsToTheSameSafeResponse() {
        ResponseEntity<ApiErrorResponse> missingRecord =
                handler.handleInternalIdempotencyException(
                        new IdempotencyRecordNotFoundException(42L)
                );
        ResponseEntity<ApiErrorResponse> missingTransaction =
                handler.handleInternalIdempotencyException(
                        new IdempotencyCompletionTransactionNotFoundException(
                                UUID.fromString(
                                        "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
                                )
                        )
                );

        assertSafeInternalError(missingRecord);
        assertSafeInternalError(missingTransaction);
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
        assertThat(response.getBody().traceId()).isNull();
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }
}
