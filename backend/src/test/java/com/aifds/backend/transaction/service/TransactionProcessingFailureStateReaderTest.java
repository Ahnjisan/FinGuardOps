package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionProcessingFailureStateReaderTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );

    @Mock private FinancialTransactionRepository transactionRepository;
    @Mock private DetectionResultRepository detectionResultRepository;
    @Mock private FinancialTransaction transaction;
    @Mock private DetectionResult detectionResult;

    private TransactionProcessingFailureStateReader reader;

    @BeforeEach
    void setUp() {
        reader = new TransactionProcessingFailureStateReader(
                transactionRepository,
                detectionResultRepository
        );
    }

    @Test
    void confirmsReceivedOnlyWhenNoDetectionResultExists() {
        stub(TransactionProcessingStatus.RECEIVED, List.of());
        assertThat(reader.read(TRANSACTION_ID)).isEqualTo(
                TransactionProcessingFailureStateReader.FailureState
                        .CONFIRMED_FAILURE
        );

        stub(TransactionProcessingStatus.RECEIVED, List.of(detectionResult));
        assertThat(reader.read(TRANSACTION_ID)).isEqualTo(
                TransactionProcessingFailureStateReader.FailureState
                        .INDETERMINATE
        );
    }

    @Test
    void confirmsFailedOnlyWithOneCorrespondingFailedResult() {
        when(detectionResult.getAnalysisStatus())
                .thenReturn(DetectionAnalysisStatus.FAILED);
        stub(TransactionProcessingStatus.FAILED, List.of(detectionResult));
        assertThat(reader.read(TRANSACTION_ID)).isEqualTo(
                TransactionProcessingFailureStateReader.FailureState
                        .CONFIRMED_FAILURE
        );

        DetectionResult extra = org.mockito.Mockito.mock(
                DetectionResult.class
        );
        stub(TransactionProcessingStatus.FAILED,
                List.of(detectionResult, extra));
        assertThat(reader.read(TRANSACTION_ID)).isEqualTo(
                TransactionProcessingFailureStateReader.FailureState
                        .INDETERMINATE
        );
    }

    @Test
    void analyzingInProgressMismatchAndMissingRemainIndeterminate() {
        stub(TransactionProcessingStatus.ANALYZING,
                List.of(detectionResult));
        assertThat(reader.read(TRANSACTION_ID)).isEqualTo(
                TransactionProcessingFailureStateReader.FailureState
                        .INDETERMINATE
        );

        clearInvocations(transactionRepository, detectionResultRepository);
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.empty());
        assertThat(reader.read(TRANSACTION_ID)).isEqualTo(
                TransactionProcessingFailureStateReader.FailureState
                        .INDETERMINATE
        );
        verify(detectionResultRepository, never())
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        TRANSACTION_ID
                );
    }

    @Test
    void usesReadOnlyRequiresNewReadCommittedWithoutLocks() throws Exception {
        Method read = TransactionProcessingFailureStateReader.class.getMethod(
                "read",
                UUID.class
        );
        Transactional boundary = read.getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.readOnly()).isTrue();
        assertThat(boundary.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(boundary.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    private void stub(
            TransactionProcessingStatus status,
            List<DetectionResult> results
    ) {
        when(transactionRepository.findByTransactionId(TRANSACTION_ID))
                .thenReturn(Optional.of(transaction));
        when(transaction.getProcessingStatus()).thenReturn(status);
        when(detectionResultRepository
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        TRANSACTION_ID
                )).thenReturn(results);
    }
}
