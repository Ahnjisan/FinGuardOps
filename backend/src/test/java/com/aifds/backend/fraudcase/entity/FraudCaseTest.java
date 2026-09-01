package com.aifds.backend.fraudcase.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void appliesEveryApprovedStatusTransitionAndKeepsFirstReviewTime() {
        FraudCase fraudCase = FraudCase.open(UUID.randomUUID(), CREATED_AT);
        String assignee = "1a000000-0000-4000-9000-000000000001";
        Instant startedAt = CREATED_AT.plusSeconds(10);
        Instant requestedAt = CREATED_AT.plusSeconds(20);
        Instant resumedAt = CREATED_AT.plusSeconds(30);

        fraudCase.startReview(assignee, startedAt);
        assertThat(fraudCase.getCaseStatus())
                .isEqualTo(FraudCaseStatus.IN_REVIEW);
        assertThat(fraudCase.getAssigneeRef()).isEqualTo(assignee);
        assertThat(fraudCase.getReviewStartedAt()).isEqualTo(startedAt);
        fraudCase.requestAdditionalInformation(requestedAt);
        assertThat(fraudCase.getCaseStatus()).isEqualTo(
                FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED
        );
        assertThat(fraudCase.getAssigneeRef()).isEqualTo(assignee);
        fraudCase.resumeReview(resumedAt);
        assertThat(fraudCase.getCaseStatus())
                .isEqualTo(FraudCaseStatus.IN_REVIEW);
        assertThat(fraudCase.getReviewStartedAt()).isEqualTo(startedAt);
        assertThat(fraudCase.getLastChangedAt()).isEqualTo(resumedAt);
        assertThat(fraudCase.getFinalDisposition()).isNull();
        assertThat(fraudCase.getClosedAt()).isNull();
    }

    @Test
    void enforcesAssigneeChangesForEachEditableState() {
        FraudCase fraudCase = FraudCase.open(UUID.randomUUID(), CREATED_AT);
        String first = "10000000-0000-4000-9000-000000000001";
        String second = "20000000-0000-4000-9000-000000000002";
        fraudCase.startReview(first, CREATED_AT.plusSeconds(1));

        fraudCase.changeAssignee(second, CREATED_AT.plusSeconds(2));
        assertThat(fraudCase.getAssigneeRef()).isEqualTo(second);
        assertThatThrownBy(() -> fraudCase.changeAssignee(
                null,
                CREATED_AT.plusSeconds(3)
        )).isInstanceOf(IllegalStateException.class);

        fraudCase.requestAdditionalInformation(CREATED_AT.plusSeconds(3));
        fraudCase.changeAssignee(null, CREATED_AT.plusSeconds(4));
        assertThat(fraudCase.getAssigneeRef()).isNull();
        fraudCase.changeAssignee(first, CREATED_AT.plusSeconds(5));
        assertThat(fraudCase.getAssigneeRef()).isEqualTo(first);
        fraudCase.changeAssignee(second, CREATED_AT.plusSeconds(6));
        assertThat(fraudCase.getAssigneeRef()).isEqualTo(second);
    }

    @Test
    void rejectsForbiddenTransitionsSameAssigneeAndInvalidWriteRef() {
        FraudCase open = FraudCase.open(UUID.randomUUID(), CREATED_AT);
        String assignee = "1a000000-0000-4000-9000-000000000001";
        assertThatThrownBy(() -> open.requestAdditionalInformation(
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.resumeReview(
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.changeAssignee(
                assignee,
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.startReview(
                assignee.toUpperCase(),
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(assignee.toUpperCase());

        open.startReview(assignee, CREATED_AT.plusSeconds(1));
        assertThatThrownBy(() -> open.startReview(
                assignee,
                CREATED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.changeAssignee(
                assignee,
                CREATED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsEveryWorkflowChangeForClosedCase() {
        FraudCase closed = FraudCase.open(UUID.randomUUID(), CREATED_AT);
        ReflectionTestUtils.setField(
                closed,
                "caseStatus",
                FraudCaseStatus.CLOSED
        );
        ReflectionTestUtils.setField(
                closed,
                "finalDisposition",
                FraudCaseFinalDisposition.NORMAL
        );
        ReflectionTestUtils.setField(
                closed,
                "closedAt",
                CREATED_AT.plusSeconds(1)
        );
        Instant changedAt = CREATED_AT.plusSeconds(2);

        assertThatThrownBy(() -> closed.startReview(
                "10000000-0000-4000-9000-000000000001",
                changedAt
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                closed.requestAdditionalInformation(changedAt)
        ).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.resumeReview(changedAt))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.changeAssignee(null, changedAt))
                .isInstanceOf(IllegalStateException.class);
    }
}
