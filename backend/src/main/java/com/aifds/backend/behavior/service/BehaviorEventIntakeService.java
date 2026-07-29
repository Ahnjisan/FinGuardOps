package com.aifds.backend.behavior.service;

import com.aifds.backend.behavior.command.ValidatedBehaviorEventCommand;
import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.exception.BehaviorEventConcurrentResultNotFoundException;
import com.aifds.backend.behavior.exception.BehaviorEventDependencyTimeoutException;
import com.aifds.backend.behavior.exception.BehaviorEventDependencyUnavailableException;
import com.aifds.backend.behavior.exception.BehaviorEventTransactionNotFoundException;
import com.aifds.backend.behavior.exception.DuplicateBehaviorEventException;
import com.aifds.backend.behavior.fingerprint.BehaviorEventRequestFingerprint;
import com.aifds.backend.behavior.validation.BehaviorEventRequestValidator;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

@Service
public class BehaviorEventIntakeService {

    private static final String POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String EVENT_ID_UNIQUE_CONSTRAINT =
            "uq_behavior_event_event_id";

    private final BehaviorEventRequestValidator validator;
    private final BehaviorEventRequestFingerprint fingerprint;
    private final BehaviorEventReplayReader replayReader;
    private final BehaviorEventIntakeWriter writer;
    private final FinancialTransactionRepository transactionRepository;

    public BehaviorEventIntakeService(
            BehaviorEventRequestValidator validator,
            BehaviorEventRequestFingerprint fingerprint,
            BehaviorEventReplayReader replayReader,
            BehaviorEventIntakeWriter writer,
            FinancialTransactionRepository transactionRepository
    ) {
        this.validator = validator;
        this.fingerprint = fingerprint;
        this.replayReader = replayReader;
        this.writer = writer;
        this.transactionRepository = transactionRepository;
    }

    public BehaviorEventIntakeResult receive(
            BehaviorEventCreateRequest request
    ) {
        ValidatedBehaviorEventCommand command = validator.validate(request);
        String requestFingerprint =
                fingerprint.calculate(command.toFingerprintInput());

        Optional<BehaviorEventIntakeSnapshot> existing =
                findExisting(command);
        if (existing.isPresent()) {
            return replayOrThrow(existing.get(), requestFingerprint);
        }

        FinancialTransaction transaction = findAndValidateTransaction(command);
        try {
            BehaviorEventIntakeSnapshot created = writer.create(
                    command,
                    requestFingerprint,
                    transaction == null ? null : transaction.getId()
            );
            return new BehaviorEventIntakeResult.Created(created);
        } catch (DataIntegrityViolationException exception) {
            if (!isEventIdUniqueViolation(exception)) {
                throw classifyDataAccessException(exception);
            }
            return replayAfterConcurrentInsert(command, requestFingerprint);
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }
    }

    private Optional<BehaviorEventIntakeSnapshot> findExisting(
            ValidatedBehaviorEventCommand command
    ) {
        try {
            return replayReader.findByEventId(command.eventId());
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }
    }

    private FinancialTransaction findAndValidateTransaction(
            ValidatedBehaviorEventCommand command
    ) {
        if (command.transactionId() == null) {
            return null;
        }

        FinancialTransaction transaction;
        try {
            transaction = transactionRepository
                    .findByTransactionId(command.transactionId())
                    .orElseThrow(
                            BehaviorEventTransactionNotFoundException::new
                    );
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }
        validator.validateRelatedTransaction(command, transaction);
        return transaction;
    }

    private BehaviorEventIntakeResult replayAfterConcurrentInsert(
            ValidatedBehaviorEventCommand command,
            String requestFingerprint
    ) {
        BehaviorEventIntakeSnapshot existing;
        try {
            existing = replayReader.findByEventId(command.eventId())
                    .orElseThrow(
                            BehaviorEventConcurrentResultNotFoundException::new
                    );
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }
        return replayOrThrow(existing, requestFingerprint);
    }

    private BehaviorEventIntakeResult replayOrThrow(
            BehaviorEventIntakeSnapshot existing,
            String requestFingerprint
    ) {
        if (!existing.requestFingerprint().equals(requestFingerprint)) {
            throw new DuplicateBehaviorEventException();
        }
        return new BehaviorEventIntakeResult.Replay(existing);
    }

    private boolean isEventIdUniqueViolation(
            DataIntegrityViolationException exception
    ) {
        boolean uniqueViolation = false;
        boolean targetConstraint = false;
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE.equals(
                    sqlException.getSQLState()
            )) {
                uniqueViolation = true;
            }
            if (current instanceof ConstraintViolationException violation
                    && EVENT_ID_UNIQUE_CONSTRAINT.equals(
                    violation.getConstraintName()
            )) {
                targetConstraint = true;
            }
            current = current.getCause();
        }
        return uniqueViolation && targetConstraint;
    }

    private RuntimeException classifyDataAccessException(
            DataAccessException exception
    ) {
        if (hasCause(exception, QueryTimeoutException.class)) {
            return new BehaviorEventDependencyTimeoutException(exception);
        }
        if (hasCause(exception, TransientDataAccessResourceException.class)
                || hasCause(
                exception,
                DataAccessResourceFailureException.class
        )) {
            return new BehaviorEventDependencyUnavailableException(exception);
        }
        return exception;
    }

    private boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> causeType
    ) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
