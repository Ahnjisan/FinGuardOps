package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String KEY = "service-unit-key";
    private static final String REQUEST_FINGERPRINT = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");

    @Mock
    private IdempotencyClaimWriter idempotencyClaimWriter;
    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock
    private FinancialTransactionRepository financialTransactionRepository;
    @Mock
    private TransactionRequestFingerprint transactionRequestFingerprint;

    private IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(
                idempotencyClaimWriter,
                idempotencyRecordRepository,
                financialTransactionRepository,
                transactionRequestFingerprint,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void returnsAcquiredWhenIndependentInsertSucceeds() {
        TransactionFingerprintInput input = fingerprintInput();
        when(transactionRequestFingerprint.calculate(input)).thenReturn(REQUEST_FINGERPRINT);
        when(idempotencyClaimWriter.createInProgress(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY,
                REQUEST_FINGERPRINT
        )).thenReturn(42L);

        assertThat(idempotencyService.claim(KEY, input))
                .isEqualTo(new IdempotencyClaimResult.Acquired(42L));
    }

    @Test
    void comparesFingerprintBeforeInterpretingCompletedStatus() {
        TransactionFingerprintInput input = fingerprintInput();
        IdempotencyRecord completed = IdempotencyRecord.inProgress(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY,
                "b".repeat(64)
        );
        completed.complete(
                transaction(input.transactionId()),
                objectMapper.createObjectNode().put("result", "completed"),
                NOW
        );
        stubScopeKeyUniqueViolation(input, completed);

        assertThat(idempotencyService.claim(KEY, input))
                .isInstanceOf(IdempotencyClaimResult.KeyConflict.class);
    }

    @Test
    void returnsInProgressForSameFingerprintAndState() {
        TransactionFingerprintInput input = fingerprintInput();
        IdempotencyRecord inProgress = IdempotencyRecord.inProgress(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY,
                REQUEST_FINGERPRINT
        );
        stubScopeKeyUniqueViolation(input, inProgress);

        assertThat(idempotencyService.claim(KEY, input))
                .isInstanceOf(IdempotencyClaimResult.InProgress.class);
    }

    @Test
    void rethrowsUniqueViolationForDifferentConstraint() {
        TransactionFingerprintInput input = fingerprintInput();
        DataIntegrityViolationException exception = violation(
                "23505",
                "uq_financial_transaction_transaction_id"
        );
        when(transactionRequestFingerprint.calculate(input)).thenReturn(REQUEST_FINGERPRINT);
        when(idempotencyClaimWriter.createInProgress(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY,
                REQUEST_FINGERPRINT
        )).thenThrow(exception);

        assertThatThrownBy(() -> idempotencyService.claim(KEY, input)).isSameAs(exception);
        verify(idempotencyRecordRepository, never())
                .findByOperationScopeAndIdempotencyKey(
                        IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                        KEY
                );
    }

    @Test
    void rethrowsTargetConstraintViolationWithNonUniqueSqlState() {
        TransactionFingerprintInput input = fingerprintInput();
        DataIntegrityViolationException exception = violation(
                "23514",
                "uq_idempotency_record_scope_key"
        );
        when(transactionRequestFingerprint.calculate(input)).thenReturn(REQUEST_FINGERPRINT);
        when(idempotencyClaimWriter.createInProgress(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY,
                REQUEST_FINGERPRINT
        )).thenThrow(exception);

        assertThatThrownBy(() -> idempotencyService.claim(KEY, input)).isSameAs(exception);
    }

    @Test
    void throwsDomainExceptionWhenCompletionTransactionDoesNotExist() {
        UUID missingTransactionId = UUID.randomUUID();
        when(financialTransactionRepository.findByTransactionId(missingTransactionId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> idempotencyService.complete(
                10L,
                missingTransactionId,
                objectMapper.createObjectNode()
        )).isInstanceOf(IdempotencyCompletionTransactionNotFoundException.class)
                .hasMessageContaining(missingTransactionId.toString());
        verify(idempotencyRecordRepository, never()).findByIdForUpdate(10L);
    }

    private void stubScopeKeyUniqueViolation(
            TransactionFingerprintInput input,
            IdempotencyRecord existing
    ) {
        when(transactionRequestFingerprint.calculate(input)).thenReturn(REQUEST_FINGERPRINT);
        when(idempotencyClaimWriter.createInProgress(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY,
                REQUEST_FINGERPRINT
        )).thenThrow(violation("23505", "uq_idempotency_record_scope_key"));
        when(idempotencyRecordRepository.findByOperationScopeAndIdempotencyKey(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                KEY
        )).thenReturn(Optional.of(existing));
    }

    private DataIntegrityViolationException violation(String sqlState, String constraint) {
        SQLException sqlException = new SQLException(
                "duplicate key value violates unique constraint \"" + constraint + "\"",
                sqlState
        );
        return new DataIntegrityViolationException("persistence constraint violation", sqlException);
    }

    private TransactionFingerprintInput fingerprintInput() {
        return new TransactionFingerprintInput(
                UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-23T01:15:30Z"),
                "cust_ref_service",
                "acct_ref_service_sender",
                "acct_ref_service_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_service"
        );
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-23T01:15:30Z"),
                "cust_ref_service",
                "acct_ref_service_sender",
                "acct_ref_service_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_service"
        );
    }
}
