package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.transaction.validation.TransactionRequestValidator;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionIntakeServiceTest {

    private static final String KEY = "intake-unit-key";
    private static final long RECORD_ID = 42L;

    @Mock
    private IdempotencyKeyValidator idempotencyKeyValidator;
    @Mock
    private TransactionRequestValidator transactionRequestValidator;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private TransactionIntakeWriter transactionIntakeWriter;

    private TransactionIntakeService transactionIntakeService;

    @BeforeEach
    void setUp() {
        transactionIntakeService = new TransactionIntakeService(
                idempotencyKeyValidator,
                transactionRequestValidator,
                idempotencyService,
                transactionIntakeWriter
        );
    }

    @Test
    void stopsBeforeRequestValidationAndClaimWhenIdempotencyKeyIsInvalid() {
        TransactionValidationException failure = validationFailure(
                TransactionValidationType.FORMAT,
                "Idempotency-Key"
        );
        when(idempotencyKeyValidator.validate(KEY)).thenThrow(failure);

        assertThatThrownBy(() -> transactionIntakeService.receive(KEY, request()))
                .isSameAs(failure);

        verifyNoInteractions(
                transactionRequestValidator,
                idempotencyService,
                transactionIntakeWriter
        );
    }

    @Test
    void stopsBeforeClaimForFormatAndDomainValidationFailures() {
        for (TransactionValidationType type : TransactionValidationType.values()) {
            TransactionCreateRequest request = request();
            TransactionValidationException failure =
                    validationFailure(type, "transactionType");
            when(idempotencyKeyValidator.validate(KEY)).thenReturn(KEY);
            doThrow(failure).when(transactionRequestValidator).validate(request);

            assertThatThrownBy(() -> transactionIntakeService.receive(KEY, request))
                    .isSameAs(failure);

            verifyNoInteractions(idempotencyService, transactionIntakeWriter);
        }
    }

    @Test
    void validatesThenClaimsAndPersistsOnlyAnAcquiredRequest() {
        TransactionCreateRequest request = request();
        ValidatedTransactionCommand command = command();
        TransactionIntakeResult.Received received =
                new TransactionIntakeResult.Received(
                        command.transactionId(),
                        RECORD_ID
                );
        when(idempotencyKeyValidator.validate(KEY)).thenReturn(KEY);
        when(transactionRequestValidator.validate(request)).thenReturn(command);
        when(idempotencyService.claim(KEY, command.toFingerprintInput()))
                .thenReturn(new IdempotencyClaimResult.Acquired(RECORD_ID));
        when(transactionIntakeWriter.saveAndLink(RECORD_ID, command))
                .thenReturn(received);

        TransactionIntakeResult result =
                transactionIntakeService.receive(KEY, request);

        assertThat(result).isSameAs(received);
        assertThat(received.transactionId()).isEqualTo(command.transactionId());
        assertThat(received.processingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(received.idempotencyRecordId()).isEqualTo(RECORD_ID);

        InOrder order = inOrder(
                idempotencyKeyValidator,
                transactionRequestValidator,
                idempotencyService,
                transactionIntakeWriter
        );
        order.verify(idempotencyKeyValidator).validate(KEY);
        order.verify(transactionRequestValidator).validate(request);
        order.verify(idempotencyService).claim(
                KEY,
                command.toFingerprintInput()
        );
        order.verify(transactionIntakeWriter).saveAndLink(RECORD_ID, command);
    }

    @Test
    void mapsNonAcquiredClaimResultsWithoutWriting() {
        assertMappedResult(
                new IdempotencyClaimResult.KeyConflict(),
                TransactionIntakeResult.KeyConflict.class
        );
        assertMappedResult(
                new IdempotencyClaimResult.InProgress(),
                TransactionIntakeResult.InProgress.class
        );

        TransactionIntakeResult completed = receiveWithClaim(
                new IdempotencyClaimResult.Completed("{\"result\":\"stored\"}")
        );
        assertThat(completed)
                .isEqualTo(new TransactionIntakeResult.CompletedReplay(
                        "{\"result\":\"stored\"}"
                ));

        TransactionIntakeResult failed = receiveWithClaim(
                new IdempotencyClaimResult.Failed("DEPENDENCY_TIMEOUT")
        );
        assertThat(failed)
                .isEqualTo(new TransactionIntakeResult.PreviousFailure(
                        "DEPENDENCY_TIMEOUT"
                ));

        verify(transactionIntakeWriter, never())
                .saveAndLink(RECORD_ID, command());
    }

    @Test
    void mapsOnlyExactPostgresqlTransactionIdUniqueViolationToDuplicate() {
        ValidatedTransactionCommand command = stubAcquired();
        DataIntegrityViolationException writerFailure = violation(
                "23505",
                "uq_financial_transaction_transaction_id"
        );
        doThrow(writerFailure).when(transactionIntakeWriter)
                .saveAndLink(RECORD_ID, command);
        when(idempotencyService.fail(
                RECORD_ID,
                TransactionIntakeService.DUPLICATE_TRANSACTION
        )).thenReturn(new IdempotencyClaimResult.Failed(
                TransactionIntakeService.DUPLICATE_TRANSACTION
        ));

        TransactionIntakeResult result =
                transactionIntakeService.receive(KEY, request());

        assertThat(result)
                .isEqualTo(new TransactionIntakeResult.DuplicateTransaction(
                        command.transactionId()
                ));
        assertThat(((TransactionIntakeResult.DuplicateTransaction) result)
                .failureCode())
                .isEqualTo(TransactionIntakeService.DUPLICATE_TRANSACTION);
    }

    @Test
    void doesNotMapPartialOrDifferentConstraintEvidenceAsDuplicate() {
        assertGeneralIntegrityFailure(violation(
                "23505",
                "uq_idempotency_record_scope_key"
        ));
        assertGeneralIntegrityFailure(violation(
                "23514",
                "uq_financial_transaction_transaction_id"
        ));
        assertGeneralIntegrityFailure(violation("23505", null));
        assertGeneralIntegrityFailure(violation(
                "23502",
                "uq_financial_transaction_transaction_id"
        ));
    }

    @Test
    void rethrowsOriginalGeneralWriterFailureAfterFailedTransition() {
        ValidatedTransactionCommand command = stubAcquired();
        RuntimeException writerFailure =
                new IllegalStateException("transaction link failed");
        doThrow(writerFailure).when(transactionIntakeWriter)
                .saveAndLink(RECORD_ID, command);
        when(idempotencyService.fail(
                RECORD_ID,
                TransactionIntakeService.TRANSACTION_INTAKE_FAILED
        )).thenReturn(new IdempotencyClaimResult.Failed(
                TransactionIntakeService.TRANSACTION_INTAKE_FAILED
        ));

        assertThatThrownBy(() -> transactionIntakeService.receive(KEY, request()))
                .isSameAs(writerFailure);
    }

    @Test
    void preservesOriginalWriterExceptionAndSuppressesFailedTransitionException() {
        ValidatedTransactionCommand command = stubAcquired();
        DataIntegrityViolationException writerFailure = violation(
                "23505",
                "uq_financial_transaction_transaction_id"
        );
        RuntimeException transitionFailure =
                new IllegalStateException("failed transition unavailable");
        doThrow(writerFailure).when(transactionIntakeWriter)
                .saveAndLink(RECORD_ID, command);
        when(idempotencyService.fail(
                RECORD_ID,
                TransactionIntakeService.DUPLICATE_TRANSACTION
        )).thenThrow(transitionFailure);

        assertThatThrownBy(() -> transactionIntakeService.receive(KEY, request()))
                .isSameAs(writerFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .containsExactly(transitionFailure));
    }

    private void assertMappedResult(
            IdempotencyClaimResult claimResult,
            Class<? extends TransactionIntakeResult> expectedType
    ) {
        assertThat(receiveWithClaim(claimResult)).isInstanceOf(expectedType);
    }

    private TransactionIntakeResult receiveWithClaim(
            IdempotencyClaimResult claimResult
    ) {
        TransactionCreateRequest request = request();
        ValidatedTransactionCommand command = command();
        when(idempotencyKeyValidator.validate(KEY)).thenReturn(KEY);
        when(transactionRequestValidator.validate(request)).thenReturn(command);
        when(idempotencyService.claim(KEY, command.toFingerprintInput()))
                .thenReturn(claimResult);

        return transactionIntakeService.receive(KEY, request);
    }

    private ValidatedTransactionCommand stubAcquired() {
        TransactionCreateRequest request = request();
        ValidatedTransactionCommand command = command();
        when(idempotencyKeyValidator.validate(KEY)).thenReturn(KEY);
        when(transactionRequestValidator.validate(request)).thenReturn(command);
        when(idempotencyService.claim(KEY, command.toFingerprintInput()))
                .thenReturn(new IdempotencyClaimResult.Acquired(RECORD_ID));
        return command;
    }

    private void assertGeneralIntegrityFailure(
            DataIntegrityViolationException writerFailure
    ) {
        ValidatedTransactionCommand command = stubAcquired();
        doThrow(writerFailure).when(transactionIntakeWriter)
                .saveAndLink(RECORD_ID, command);
        when(idempotencyService.fail(
                RECORD_ID,
                TransactionIntakeService.TRANSACTION_INTAKE_FAILED
        )).thenReturn(new IdempotencyClaimResult.Failed(
                TransactionIntakeService.TRANSACTION_INTAKE_FAILED
        ));

        assertThatThrownBy(() -> transactionIntakeService.receive(KEY, request()))
                .isSameAs(writerFailure);
    }

    private DataIntegrityViolationException violation(
            String sqlState,
            String constraintName
    ) {
        SQLException sqlException =
                new SQLException("database constraint violation", sqlState);
        ConstraintViolationException hibernateException =
                new ConstraintViolationException(
                        "could not execute statement",
                        sqlException,
                        "insert into financial_transaction",
                        constraintName
                );
        return new DataIntegrityViolationException(
                "persistence constraint violation",
                hibernateException
        );
    }

    private TransactionValidationException validationFailure(
            TransactionValidationType type,
            String field
    ) {
        return new TransactionValidationException(
                type,
                field,
                "TEST_VALIDATION_FAILURE",
                "validation failed"
        );
    }

    private TransactionCreateRequest request() {
        ValidatedTransactionCommand command = command();
        return new TransactionCreateRequest(
                command.transactionId().toString(),
                command.transactionType().name(),
                command.amount().toPlainString(),
                command.currencyCode(),
                command.occurredAt().toString(),
                command.externalCustomerRef(),
                command.senderAccountRef(),
                command.recipientAccountRef(),
                command.channel().name(),
                command.deviceRef()
        );
    }

    private ValidatedTransactionCommand command() {
        return new ValidatedTransactionCommand(
                UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-23T01:10:00Z"),
                "cust_ref_intake_unit",
                "acct_ref_intake_unit_sender",
                "acct_ref_intake_unit_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_intake_unit"
        );
    }
}
