package com.aifds.backend.fraudcase.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FraudCaseTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-18T02:00:00.123456Z");

    @Test
    void createsOpenCaseWithApprovedInitialState() {
        UUID caseId = UUID.randomUUID();

        FraudCase fraudCase = FraudCase.open(caseId, CREATED_AT);

        assertThat(fraudCase.getCaseId()).isEqualTo(caseId);
        assertThat(fraudCase.getCaseStatus())
                .isEqualTo(FraudCaseStatus.OPEN);
        assertThat(fraudCase.getFinalDisposition()).isNull();
        assertThat(fraudCase.getAssigneeRef()).isNull();
        assertThat(fraudCase.getReviewStartedAt()).isNull();
        assertThat(fraudCase.getClosedAt()).isNull();
        assertThat(fraudCase.getConcurrencyVersion()).isZero();
        assertThat(fraudCase.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(fraudCase.getLastChangedAt()).isEqualTo(CREATED_AT);
        assertThat(fraudCase.isActive()).isTrue();
    }

    @Test
    void rejectsNullOrNonVersionFourCaseId() {
        assertThatNullPointerException()
                .isThrownBy(() -> FraudCase.open(null, CREATED_AT))
                .withMessage("caseId must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FraudCase.open(
                        UUID.fromString(
                                "00000000-0000-1000-8000-000000000001"
                        ),
                        CREATED_AT
                ))
                .withMessage("caseId must be a UUID v4");
    }

    @Test
    void rejectsTimestampBeyondPostgresqlMicrosecondPrecision() {
        Instant nanosecondTimestamp =
                Instant.parse("2026-08-18T02:00:00.123456789Z");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FraudCase.open(
                        UUID.randomUUID(),
                        nanosecondTimestamp
                ))
                .withMessage("createdAt must have microsecond precision");
    }

    @Test
    void declaresExactStatusAndDispositionValues() {
        assertThat(FraudCaseStatus.values()).containsExactly(
                FraudCaseStatus.OPEN,
                FraudCaseStatus.IN_REVIEW,
                FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                FraudCaseStatus.CLOSED
        );
        assertThat(FraudCaseFinalDisposition.values()).containsExactly(
                FraudCaseFinalDisposition.NORMAL,
                FraudCaseFinalDisposition.FALSE_POSITIVE,
                FraudCaseFinalDisposition.CONFIRMED_FRAUD
        );
        assertThat(FraudCaseStatus.CLOSED.isActive()).isFalse();
    }
}
