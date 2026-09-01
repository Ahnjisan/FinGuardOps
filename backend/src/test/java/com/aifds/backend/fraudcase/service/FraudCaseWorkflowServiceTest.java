package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseWorkflowException;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationException;
import com.aifds.backend.fraudcase.validation.FraudCaseWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudCaseWorkflowServiceTest {

    private static final UUID CASE_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000001"
    );
    private static final String FIRST_ASSIGNEE =
            "20000000-0000-4000-9000-000000000002";
    private static final String SECOND_ASSIGNEE =
            "30000000-0000-4000-9000-000000000003";
    private static final Instant CREATED_AT =
            Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-09-01T00:10:00.123456Z");

    private final FraudCaseRepository repository =
            mock(FraudCaseRepository.class);
    private final AuditLogPersistenceService auditService =
            mock(AuditLogPersistenceService.class);
    private final FraudCaseWorkflowValidator validator =
            new FraudCaseWorkflowValidator();
    private final FraudCaseWorkflowService service =
            new FraudCaseWorkflowService(
                    repository,
                    validator,
                    new FraudCaseWorkflowMapper(),
                    auditService,
                    new ObjectMapper(),
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @BeforeEach
    void resetMocks() {
        reset(repository, auditService);
        doAnswer(invocation -> {
            FraudCase fraudCase = repository.findByCaseId(CASE_ID).orElseThrow();
            ReflectionTestUtils.setField(
                    fraudCase,
                    "concurrencyVersion",
                    fraudCase.getConcurrencyVersion() + 1
            );
            return null;
        }).when(repository).flush();
    }

    @ParameterizedTest
    @MethodSource("allowedStatusTransitions")
    void appliesEveryAllowedStatusTransitionWithOneAudit(
            FraudCaseStatus current,
            FraudCaseStatus target,
            AuditReasonCode reasonCode,
            boolean assigneePresent
    ) {
        FraudCase fraudCase = caseIn(current, true);
        Instant firstReviewTime = fraudCase.getReviewStartedAt();
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(fraudCase));

        FraudCaseMutationResponse response = service.changeStatus(
                new FraudCaseWorkflowCommand.StatusChange(
                        CASE_ID,
                        target,
                        assigneePresent,
                        assigneePresent ? FIRST_ASSIGNEE : null,
                        reasonCode,
                        0L
                ),
                "trace_case_service_01"
        );

        assertThat(response.caseStatus()).isEqualTo(target);
        assertThat(response.concurrencyVersion()).isEqualTo(1L);
        assertThat(response.finalDisposition()).isNull();
        assertThat(response.closedAt()).isNull();
        if (current == FraudCaseStatus.OPEN) {
            assertThat(response.reviewStartedAt()).isEqualTo(NOW);
        } else {
            assertThat(response.reviewStartedAt()).isEqualTo(firstReviewTime);
        }
        verify(repository).flush();
        verify(auditService).append(forClass(AuditLogDraft.class).capture());
    }

    @ParameterizedTest
    @MethodSource("forbiddenStatusTransitions")
    void rejectsEveryOtherStatusTransitionWithoutFlushOrAudit(
            FraudCaseStatus current,
            FraudCaseStatus target,
            FraudCaseWorkflowException.Reason expectedReason
    ) {
        FraudCase fraudCase = caseIn(current, true);
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(fraudCase));

        assertThatThrownBy(() -> service.changeStatus(
                new FraudCaseWorkflowCommand.StatusChange(
                        CASE_ID,
                        target,
                        false,
                        null,
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        0L
                ),
                "trace_case_service_02"
        )).isInstanceOf(FraudCaseWorkflowException.class)
                .extracting(exception ->
                        ((FraudCaseWorkflowException) exception).getReason()
                ).isEqualTo(expectedReason);
        verify(repository, never()).flush();
        verify(auditService, never()).append(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @ParameterizedTest
    @MethodSource("allowedAssigneeChanges")
    void appliesAllowedAssigneeMatrixWithExactReasonAndOneAudit(
            FraudCaseStatus status,
            boolean startsAssigned,
            String requestedAssignee,
            AuditReasonCode reasonCode
    ) {
        FraudCase fraudCase = caseIn(status, startsAssigned);
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(fraudCase));

        FraudCaseMutationResponse response = service.changeAssignee(
                new FraudCaseWorkflowCommand.AssigneeChange(
                        CASE_ID,
                        requestedAssignee,
                        reasonCode,
                        0L
                ),
                "trace_case_service_03"
        );

        assertThat(response.assigneeRef()).isEqualTo(requestedAssignee);
        assertThat(response.concurrencyVersion()).isEqualTo(1L);
        var captor = forClass(AuditLogDraft.class);
        verify(auditService, times(1)).append(captor.capture());
        assertThat(captor.getValue().action())
                .isEqualTo(AuditAction.CASE_ASSIGNEE_CHANGED);
        assertThat(captor.getValue().reasonCode()).isEqualTo(reasonCode);
        assertThat(captor.getValue().transactionId()).isNull();
        assertThat(captor.getValue().targetId()).isEqualTo(CASE_ID);
        assertThat(captor.getValue().caseId()).isEqualTo(CASE_ID);
        assertThat(captor.getValue().metadata()).isEmpty();
    }

    @Test
    void prioritizesStaleVersionOverSameStatusAssigneeAndClosedChecks() {
        FraudCase inReview = caseIn(FraudCaseStatus.IN_REVIEW, true);
        ReflectionTestUtils.setField(inReview, "concurrencyVersion", 4L);
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(inReview));

        assertConcurrent(() -> service.changeStatus(
                new FraudCaseWorkflowCommand.StatusChange(
                        CASE_ID,
                        FraudCaseStatus.IN_REVIEW,
                        false,
                        null,
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        3L
                ),
                "trace_case_service_04"
        ));
        assertConcurrent(() -> service.changeAssignee(
                new FraudCaseWorkflowCommand.AssigneeChange(
                        CASE_ID,
                        FIRST_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                        3L
                ),
                "trace_case_service_04"
        ));
        verify(repository, never()).flush();
        verify(auditService, never()).append(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsSameAssigneeReleaseInReviewAndOpenAssignment() {
        assertAssigneeConflict(
                caseIn(FraudCaseStatus.IN_REVIEW, true),
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_ASSIGNEE_CHANGED
        );
        assertAssigneeConflict(
                caseIn(FraudCaseStatus.IN_REVIEW, true),
                null,
                AuditReasonCode.CASE_ASSIGNEE_RELEASED
        );
        assertAssigneeConflict(
                caseIn(FraudCaseStatus.OPEN, false),
                FIRST_ASSIGNEE,
                AuditReasonCode.CASE_ASSIGNEE_ASSIGNED
        );
    }

    @Test
    void rejectsResumeWithoutAssigneeAndWrongReasonCombination() {
        FraudCase unassigned = caseIn(
                FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                false
        );
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(unassigned));
        assertThatThrownBy(() -> service.changeStatus(
                new FraudCaseWorkflowCommand.StatusChange(
                        CASE_ID,
                        FraudCaseStatus.IN_REVIEW,
                        false,
                        null,
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        0L
                ),
                "trace_case_service_05"
        )).isInstanceOf(FraudCaseWorkflowException.class)
                .extracting(exception ->
                        ((FraudCaseWorkflowException) exception).getReason()
                ).isEqualTo(
                        FraudCaseWorkflowException.Reason.ASSIGNEE_REQUIRED
                );

        FraudCase assigned = caseIn(FraudCaseStatus.IN_REVIEW, true);
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(assigned));
        assertThatThrownBy(() -> service.changeStatus(
                new FraudCaseWorkflowCommand.StatusChange(
                        CASE_ID,
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        false,
                        null,
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        0L
                ),
                "trace_case_service_05"
        )).isInstanceOf(FraudCaseValidationException.class);
        verify(repository, never()).flush();
    }

    @Test
    void returnsNotFoundBeforeVersionAndBusinessChecks() {
        when(repository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeAssignee(
                new FraudCaseWorkflowCommand.AssigneeChange(
                        CASE_ID,
                        FIRST_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_ASSIGNED,
                        Long.MAX_VALUE
                ),
                "trace_case_service_06"
        )).isInstanceOf(FraudCaseNotFoundException.class);
    }

    @Test
    void classifiesOptimisticTimeoutAndUnavailableWithoutLeakingDetails() {
        FraudCase open = caseIn(FraudCaseStatus.OPEN, false);
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(open));
        doThrow(new ObjectOptimisticLockingFailureException(
                FraudCase.class,
                99L
        )).when(repository).flush();
        assertWorkflowReason(
                () -> approvedStart(),
                FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION
        );

        reset(repository, auditService);
        when(repository.findByCaseId(CASE_ID)).thenThrow(
                new QueryTimeoutException("SELECT credential FROM fraud_case")
        );
        assertWorkflowReason(
                () -> approvedStart(),
                FraudCaseWorkflowException.Reason.DEPENDENCY_TIMEOUT
        );

        reset(repository, auditService);
        when(repository.findByCaseId(CASE_ID)).thenThrow(
                new DataAccessResourceFailureException("password=secret")
        );
        assertWorkflowReason(
                () -> approvedStart(),
                FraudCaseWorkflowException.Reason.DEPENDENCY_UNAVAILABLE
        );
    }

    private FraudCaseMutationResponse approvedStart() {
        return service.changeStatus(
                new FraudCaseWorkflowCommand.StatusChange(
                        CASE_ID,
                        FraudCaseStatus.IN_REVIEW,
                        true,
                        FIRST_ASSIGNEE,
                        AuditReasonCode.CASE_REVIEW_STARTED,
                        0L
                ),
                "trace_case_service_07"
        );
    }

    private void assertAssigneeConflict(
            FraudCase fraudCase,
            String requested,
            AuditReasonCode reasonCode
    ) {
        reset(repository, auditService);
        when(repository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(fraudCase));
        assertWorkflowReason(
                () -> service.changeAssignee(
                        new FraudCaseWorkflowCommand.AssigneeChange(
                                CASE_ID, requested, reasonCode, 0L
                        ),
                        "trace_case_service_08"
                ),
                FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT
        );
        verify(repository, never()).flush();
        verify(auditService, never()).append(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private void assertConcurrent(Runnable invocation) {
        assertWorkflowReason(
                invocation,
                FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION
        );
    }

    private void assertWorkflowReason(
            Runnable invocation,
            FraudCaseWorkflowException.Reason reason
    ) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(FraudCaseWorkflowException.class)
                .extracting(exception ->
                        ((FraudCaseWorkflowException) exception).getReason()
                ).isEqualTo(reason);
    }

    private FraudCase caseIn(FraudCaseStatus status, boolean assigned) {
        FraudCase fraudCase = FraudCase.open(CASE_ID, CREATED_AT);
        if (status == FraudCaseStatus.OPEN) {
            return fraudCase;
        }
        fraudCase.startReview(
                FIRST_ASSIGNEE,
                CREATED_AT.plusSeconds(1)
        );
        if (status == FraudCaseStatus.IN_REVIEW) {
            return fraudCase;
        }
        fraudCase.requestAdditionalInformation(CREATED_AT.plusSeconds(2));
        if (!assigned) {
            fraudCase.changeAssignee(null, CREATED_AT.plusSeconds(3));
        }
        if (status == FraudCaseStatus.CLOSED) {
            ReflectionTestUtils.setField(
                    fraudCase,
                    "caseStatus",
                    FraudCaseStatus.CLOSED
            );
            ReflectionTestUtils.setField(
                    fraudCase,
                    "finalDisposition",
                    FraudCaseFinalDisposition.NORMAL
            );
            ReflectionTestUtils.setField(
                    fraudCase,
                    "closedAt",
                    CREATED_AT.plusSeconds(4)
            );
        }
        return fraudCase;
    }

    private static Stream<Arguments> allowedStatusTransitions() {
        return Stream.of(
                Arguments.of(
                        FraudCaseStatus.OPEN,
                        FraudCaseStatus.IN_REVIEW,
                        AuditReasonCode.CASE_REVIEW_STARTED,
                        true
                ),
                Arguments.of(
                        FraudCaseStatus.IN_REVIEW,
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        AuditReasonCode.CASE_ADDITIONAL_INFORMATION_REQUESTED,
                        false
                ),
                Arguments.of(
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        FraudCaseStatus.IN_REVIEW,
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        false
                )
        );
    }

    private static Stream<Arguments> forbiddenStatusTransitions() {
        return Stream.of(FraudCaseStatus.values()).flatMap(current ->
                Stream.of(FraudCaseStatus.values())
                        .filter(target -> !isAllowed(current, target))
                        .map(target -> Arguments.of(
                                current,
                                target,
                                current == FraudCaseStatus.CLOSED
                                        ? FraudCaseWorkflowException.Reason
                                        .CASE_ALREADY_CLOSED
                                        : FraudCaseWorkflowException.Reason
                                        .CASE_STATUS_CONFLICT
                        ))
        );
    }

    private static boolean isAllowed(
            FraudCaseStatus current,
            FraudCaseStatus target
    ) {
        return (current == FraudCaseStatus.OPEN
                && target == FraudCaseStatus.IN_REVIEW)
                || (current == FraudCaseStatus.IN_REVIEW
                && target
                == FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED)
                || (current
                == FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED
                && target == FraudCaseStatus.IN_REVIEW);
    }

    private static Stream<Arguments> allowedAssigneeChanges() {
        return Stream.of(
                Arguments.of(
                        FraudCaseStatus.IN_REVIEW,
                        true,
                        SECOND_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED
                ),
                Arguments.of(
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        false,
                        SECOND_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_ASSIGNED
                ),
                Arguments.of(
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        true,
                        SECOND_ASSIGNEE,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED
                ),
                Arguments.of(
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        true,
                        null,
                        AuditReasonCode.CASE_ASSIGNEE_RELEASED
                )
        );
    }
}
