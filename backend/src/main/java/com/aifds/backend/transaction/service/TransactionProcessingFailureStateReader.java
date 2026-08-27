package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class TransactionProcessingFailureStateReader {

    private final FinancialTransactionRepository transactionRepository;
    private final DetectionResultRepository detectionResultRepository;

    public TransactionProcessingFailureStateReader(
            FinancialTransactionRepository transactionRepository,
            DetectionResultRepository detectionResultRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.detectionResultRepository = detectionResultRepository;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            readOnly = true
    )
    public FailureState read(UUID transactionId) {
        UUID requestedTransactionId = Objects.requireNonNull(
                transactionId,
                "transactionId must not be null"
        );
        FinancialTransaction transaction = transactionRepository
                .findByTransactionId(requestedTransactionId)
                .orElse(null);
        if (transaction == null) {
            return FailureState.INDETERMINATE;
        }

        List<DetectionResult> results = detectionResultRepository
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        requestedTransactionId
                );
        if (transaction.getProcessingStatus()
                == TransactionProcessingStatus.RECEIVED
                && results.isEmpty()) {
            return FailureState.CONFIRMED_FAILURE;
        }
        if (transaction.getProcessingStatus()
                == TransactionProcessingStatus.FAILED
                && results.size() == 1
                && results.get(0).getAnalysisStatus()
                == DetectionAnalysisStatus.FAILED) {
            return FailureState.CONFIRMED_FAILURE;
        }
        return FailureState.INDETERMINATE;
    }

    public enum FailureState {
        CONFIRMED_FAILURE,
        INDETERMINATE
    }
}
