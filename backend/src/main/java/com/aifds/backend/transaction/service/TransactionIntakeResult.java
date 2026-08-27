package com.aifds.backend.transaction.service;

import java.util.Objects;
import java.util.UUID;

public sealed interface TransactionIntakeResult {

    String DEPENDENCY_MESSAGE = "탐지 서비스를 사용할 수 없습니다.";
    String INTERNAL_ERROR_MESSAGE =
            "요청을 처리하는 중 오류가 발생했습니다.";

    record Received(
            TransactionIntakeSnapshot snapshot,
            int httpStatus
    )
            implements TransactionIntakeResult {

        public Received {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (httpStatus
                    != TransactionIntakeSnapshotEnvelopeCodec.SUPPORTED_HTTP_STATUS) {
                throw new IllegalArgumentException(
                        "New transaction intake HTTP status must be 201"
                );
            }
        }
    }

    record KeyConflict() implements TransactionIntakeResult {
    }

    record InProgress() implements TransactionIntakeResult {
    }

    record CompletedReplay(
            TransactionIntakeSnapshot snapshot,
            int httpStatus
    )
            implements TransactionIntakeResult {

        public CompletedReplay {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (httpStatus != 200 && httpStatus != 201) {
                throw new IllegalArgumentException(
                        "Completed replay HTTP status must be 200 or 201"
                );
            }
        }
    }

    record PreviousFailure(
            String failureCode
    ) implements TransactionIntakeResult {
    }

    record ExternalRiskFailure(
            int httpStatus,
            String code,
            String message
    ) implements TransactionIntakeResult {

        public ExternalRiskFailure {
            validateTypedFailure(httpStatus, code, message);
        }
    }

    record ExternalRiskFailureReplay(
            int httpStatus,
            String code,
            String message
    ) implements TransactionIntakeResult {

        public ExternalRiskFailureReplay {
            validateTypedFailure(httpStatus, code, message);
        }
    }

    record ProviderUnavailable() implements TransactionIntakeResult {
    }

    record RuleFailure() implements TransactionIntakeResult {
    }

    record DuplicateTransaction(
            UUID transactionId
    ) implements TransactionIntakeResult {

        public DuplicateTransaction {
            Objects.requireNonNull(transactionId, "transactionId must not be null");
        }

        public String failureCode() {
            return TransactionIntakeService.DUPLICATE_TRANSACTION;
        }
    }

    private static void validateTypedFailure(
            int httpStatus,
            String code,
            String message
    ) {
        if (httpStatus != 500 && httpStatus != 503) {
            throw new IllegalArgumentException(
                    "Typed failure HTTP status must be 500 or 503"
            );
        }
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        boolean dependencyFailure = httpStatus == 503
                && ("DEPENDENCY_TIMEOUT".equals(code)
                || "DEPENDENCY_UNAVAILABLE".equals(code))
                && DEPENDENCY_MESSAGE.equals(message);
        boolean internalFailure = httpStatus == 500
                && "INTERNAL_ERROR".equals(code)
                && INTERNAL_ERROR_MESSAGE.equals(message);
        if (!dependencyFailure && !internalFailure) {
            throw new IllegalArgumentException(
                    "Typed failure public mapping is not approved"
            );
        }
    }
}
