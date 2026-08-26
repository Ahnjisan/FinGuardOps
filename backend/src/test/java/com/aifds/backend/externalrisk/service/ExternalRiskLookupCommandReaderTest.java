package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalRiskLookupCommandReaderTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "53000000-0000-4000-8000-000000000001"
    );
    private static final String TRACE_ID = "trace-ext-risk-reader-0001";
    private static final Instant OCCURRED_AT = Instant.parse(
            "2026-08-26T01:00:00.123456Z"
    );

    @Mock
    private FinancialTransactionRepository transactionRepository;

    @Mock
    private FinancialTransaction transaction;

    @Test
    void readsReceivedTransactionWithoutLockAndBuildsExactImmutableCommand() {
        configureReceivedTransaction();
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.of(transaction));

        ExternalRiskLookupCommand command = reader().read(
                TRANSACTION_ID,
                TRACE_ID
        );

        assertThat(command.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(command.transactionType())
                .isEqualTo(TransactionType.ACCOUNT_TRANSFER);
        assertThat(command.evaluationCutoffAt()).isEqualTo(OCCURRED_AT);
        assertThat(command.externalCustomerRef()).isEqualTo("customer-ref");
        assertThat(command.senderAccountRef()).isEqualTo("sender-ref");
        assertThat(command.recipientAccountRef()).isEqualTo("recipient-ref");
        assertThat(command.deviceRef()).isEqualTo("device-ref");
        assertThat(command.traceId()).isEqualTo(TRACE_ID);
        verify(transactionRepository).findByTransactionId(TRANSACTION_ID);
        verify(transactionRepository, never())
                .findByTransactionIdForUpdate(TRANSACTION_ID);
    }

    @Test
    void rejectsMissingTransactionWithSafeIllegalArgumentException() {
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader().read(TRANSACTION_ID, TRACE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction was not found");
        verify(transactionRepository, never())
                .findByTransactionIdForUpdate(TRANSACTION_ID);
    }

    @Test
    void rejectsNonReceivedTransactionBeforeBuildingCommand() {
        when(transaction.getProcessingStatus())
                .thenReturn(TransactionProcessingStatus.ANALYZING);
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> reader().read(TRANSACTION_ID, TRACE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transaction processing status must be RECEIVED");
        verify(transactionRepository, never())
                .findByTransactionIdForUpdate(TRANSACTION_ID);
    }

    @Test
    void usesAReadOnlyReadCommittedRequiredTransactionOnThePublicMethod()
            throws Exception {
        assertThat(ExternalRiskLookupCommandReader.class
                .getAnnotation(Service.class)).isNotNull();
        Method read = ExternalRiskLookupCommandReader.class.getMethod(
                "read",
                UUID.class,
                String.class
        );
        Transactional transactional = read.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation())
                .isEqualTo(Isolation.READ_COMMITTED);
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRED);
    }

    private ExternalRiskLookupCommandReader reader() {
        return new ExternalRiskLookupCommandReader(transactionRepository);
    }

    private void configureReceivedTransaction() {
        when(transaction.getProcessingStatus())
                .thenReturn(TransactionProcessingStatus.RECEIVED);
        when(transaction.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(transaction.getTransactionType())
                .thenReturn(TransactionType.ACCOUNT_TRANSFER);
        when(transaction.getOccurredAt()).thenReturn(OCCURRED_AT);
        when(transaction.getExternalCustomerRef()).thenReturn("customer-ref");
        when(transaction.getSenderAccountRef()).thenReturn("sender-ref");
        when(transaction.getRecipientAccountRef())
                .thenReturn("recipient-ref");
        when(transaction.getDeviceRef()).thenReturn("device-ref");
    }
}
