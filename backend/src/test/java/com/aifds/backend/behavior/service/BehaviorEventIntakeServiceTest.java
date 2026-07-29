package com.aifds.backend.behavior.service;

import com.aifds.backend.behavior.command.ValidatedBehaviorEventCommand;
import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.exception.BehaviorEventConcurrentResultNotFoundException;
import com.aifds.backend.behavior.exception.BehaviorEventDependencyTimeoutException;
import com.aifds.backend.behavior.exception.BehaviorEventDependencyUnavailableException;
import com.aifds.backend.behavior.exception.BehaviorEventTransactionNotFoundException;
import com.aifds.backend.behavior.exception.DuplicateBehaviorEventException;
import com.aifds.backend.behavior.fingerprint.BehaviorEventRequestFingerprint;
import com.aifds.backend.behavior.validation.BehaviorEventRequestValidator;
import com.aifds.backend.behavior.validation.BehaviorEventValidationException;
import com.aifds.backend.behavior.validation.BehaviorEventValidationType;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BehaviorEventIntakeServiceTest {

    private static final UUID EVENT_ID = UUID.fromString(
            "e54cbf7e-d857-4ca0-bff3-8d4321b7722a"
    );
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-07-29T04:10:00Z");
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T04:10:01Z");
    private static final String FINGERPRINT = "a".repeat(64);

    private final BehaviorEventRequestValidator validator =
            mock(BehaviorEventRequestValidator.class);
    private final BehaviorEventRequestFingerprint fingerprint =
            mock(BehaviorEventRequestFingerprint.class);
    private final BehaviorEventReplayReader replayReader =
            mock(BehaviorEventReplayReader.class);
    private final BehaviorEventIntakeWriter writer =
            mock(BehaviorEventIntakeWriter.class);
    private final FinancialTransactionRepository transactionRepository =
            mock(FinancialTransactionRepository.class);

    private BehaviorEventIntakeService service;

    @BeforeEach
    void setUp() {
        service = new BehaviorEventIntakeService(
                validator,
                fingerprint,
                replayReader,
                writer,
                transactionRepository
        );
    }

    @Test
    void createsNewEventAfterValidationAndFingerprinting() {
        ValidatedBehaviorEventCommand command = command(null);
        BehaviorEventIntakeSnapshot snapshot = snapshot(FINGERPRINT, null);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(writer.create(command, FINGERPRINT, null)).thenReturn(snapshot);

        BehaviorEventIntakeResult result = service.receive(request());

        assertThat(result).isEqualTo(
                new BehaviorEventIntakeResult.Created(snapshot)
        );
        verify(writer).create(command, FINGERPRINT, null);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void replaysSameEventWithoutLookingUpTransactionOrWriting() {
        ValidatedBehaviorEventCommand command = command(TRANSACTION_ID);
        BehaviorEventIntakeSnapshot snapshot =
                snapshot(FINGERPRINT, TRANSACTION_ID);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID))
                .thenReturn(Optional.of(snapshot));

        BehaviorEventIntakeResult result = service.receive(request());

        assertThat(result).isEqualTo(
                new BehaviorEventIntakeResult.Replay(snapshot)
        );
        verifyNoInteractions(transactionRepository, writer);
    }

    @Test
    void rejectsSameEventIdWithDifferentFingerprint() {
        ValidatedBehaviorEventCommand command = command(null);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID))
                .thenReturn(Optional.of(snapshot("b".repeat(64), null)));

        assertThatThrownBy(() -> service.receive(request()))
                .isInstanceOf(DuplicateBehaviorEventException.class);

        verifyNoInteractions(transactionRepository, writer);
    }

    @Test
    void failsBeforeAnyRepositoryCallWhenValidationFails() {
        BehaviorEventValidationException failure =
                new BehaviorEventValidationException(
                        BehaviorEventValidationType.DOMAIN,
                        "accountRef",
                        "ACCOUNT_REF_REQUIRED",
                        "required"
                );
        when(validator.validate(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.receive(request())).isSameAs(failure);

        verifyNoInteractions(
                fingerprint,
                replayReader,
                writer,
                transactionRepository
        );
    }

    @Test
    void reportsMissingRelatedTransaction() {
        ValidatedBehaviorEventCommand command = command(TRANSACTION_ID);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(request()))
                .isInstanceOf(BehaviorEventTransactionNotFoundException.class);

        verifyNoInteractions(writer);
    }

    @Test
    void validatesRelatedTransactionAndPassesOnlyItsInternalIdToWriter() {
        ValidatedBehaviorEventCommand command = command(TRANSACTION_ID);
        FinancialTransaction transaction = transaction();
        ReflectionTestUtils.setField(transaction, "id", 27L);
        BehaviorEventIntakeSnapshot snapshot =
                snapshot(FINGERPRINT, TRANSACTION_ID);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.of(transaction));
        when(writer.create(command, FINGERPRINT, 27L)).thenReturn(snapshot);

        service.receive(request());

        verify(validator).validateRelatedTransaction(command, transaction);
        verify(writer).create(command, FINGERPRINT, 27L);
    }

    @Test
    void concurrentWinnerWithSameFingerprintBecomesReplay() {
        ValidatedBehaviorEventCommand command = command(null);
        BehaviorEventIntakeSnapshot snapshot = snapshot(FINGERPRINT, null);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID))
                .thenReturn(Optional.empty(), Optional.of(snapshot));
        when(writer.create(command, FINGERPRINT, null))
                .thenThrow(eventIdUniqueViolation());

        BehaviorEventIntakeResult result = service.receive(request());

        assertThat(result).isEqualTo(
                new BehaviorEventIntakeResult.Replay(snapshot)
        );
        verify(replayReader, org.mockito.Mockito.times(2))
                .findByEventId(EVENT_ID);
    }

    @Test
    void concurrentWinnerWithDifferentFingerprintBecomesConflict() {
        ValidatedBehaviorEventCommand command = command(null);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(snapshot("b".repeat(64), null))
                );
        when(writer.create(command, FINGERPRINT, null))
                .thenThrow(eventIdUniqueViolation());

        assertThatThrownBy(() -> service.receive(request()))
                .isInstanceOf(DuplicateBehaviorEventException.class);
    }

    @Test
    void missingConcurrentWinnerIsInternalInconsistency() {
        ValidatedBehaviorEventCommand command = command(null);
        arrange(command);
        when(replayReader.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(writer.create(command, FINGERPRINT, null))
                .thenThrow(eventIdUniqueViolation());

        assertThatThrownBy(() -> service.receive(request()))
                .isInstanceOf(
                        BehaviorEventConcurrentResultNotFoundException.class
                );
    }

    @Test
    void classifiesTimeoutUnavailableAndOtherDataAccessSeparately() {
        ValidatedBehaviorEventCommand command = command(null);
        arrange(command);

        doThrow(new QueryTimeoutException("timeout"))
                .when(replayReader).findByEventId(EVENT_ID);
        assertThatThrownBy(() -> service.receive(request()))
                .isInstanceOf(BehaviorEventDependencyTimeoutException.class);

        doThrow(new DataAccessResourceFailureException("down"))
                .when(replayReader).findByEventId(EVENT_ID);
        assertThatThrownBy(() -> service.receive(request()))
                .isInstanceOf(
                        BehaviorEventDependencyUnavailableException.class
                );

        InvalidDataAccessResourceUsageException other =
                new InvalidDataAccessResourceUsageException("other");
        doThrow(other).when(replayReader).findByEventId(EVENT_ID);
        assertThatThrownBy(() -> service.receive(request())).isSameAs(other);
    }

    private void arrange(ValidatedBehaviorEventCommand command) {
        when(validator.validate(any())).thenReturn(command);
        when(fingerprint.calculate(command.toFingerprintInput()))
                .thenReturn(FINGERPRINT);
    }

    private static DataIntegrityViolationException eventIdUniqueViolation() {
        SQLException sqlException = new SQLException(
                "unique violation",
                "23505"
        );
        ConstraintViolationException constraintViolation =
                new ConstraintViolationException(
                        "constraint violation",
                        sqlException,
                        "uq_behavior_event_event_id"
                );
        return new DataIntegrityViolationException(
                "integrity violation",
                constraintViolation
        );
    }

    private static BehaviorEventCreateRequest request() {
        return new BehaviorEventCreateRequest(
                EVENT_ID.toString(),
                BehaviorEventType.LOGIN_FAILED.name(),
                OCCURRED_AT.toString(),
                "customer",
                null,
                null,
                null,
                null
        );
    }

    private static ValidatedBehaviorEventCommand command(UUID transactionId) {
        return new ValidatedBehaviorEventCommand(
                EVENT_ID,
                BehaviorEventType.LOGIN_FAILED,
                OCCURRED_AT,
                "customer",
                null,
                null,
                transactionId,
                null
        );
    }

    private static BehaviorEventIntakeSnapshot snapshot(
            String requestFingerprint,
            UUID transactionId
    ) {
        return new BehaviorEventIntakeSnapshot(
                EVENT_ID,
                BehaviorEventType.LOGIN_FAILED,
                transactionId,
                OCCURRED_AT,
                CREATED_AT,
                requestFingerprint
        );
    }

    private static FinancialTransaction transaction() {
        return new FinancialTransaction(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.ONE,
                "KRW",
                OCCURRED_AT,
                "customer",
                "sender",
                "recipient",
                TransactionChannel.MOBILE_BANKING,
                null
        );
    }
}
