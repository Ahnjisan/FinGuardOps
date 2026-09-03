package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.entity.AuditLog;
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
import com.aifds.backend.fraudcase.validation.FraudCaseWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;

@Service
public class FraudCaseWorkflowService {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseWorkflowValidator validator;
    private final FraudCaseWorkflowMapper mapper;
    private final AuditLogPersistenceService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FraudCaseWorkflowService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseWorkflowValidator validator,
            FraudCaseWorkflowMapper mapper,
            AuditLogPersistenceService auditLogService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.validator = validator;
        this.mapper = mapper;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + CASE_WORKFLOW_WRITE + "')")
    public FraudCaseMutationResponse changeStatus(
            FraudCaseWorkflowCommand.StatusChange command,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            FraudCase fraudCase = findCase(command.caseId());
            requireExpectedVersion(fraudCase, command.expectedVersion());
            requireOpenForChange(fraudCase);

            FraudCaseStatus beforeStatus = fraudCase.getCaseStatus();
            String beforeAssignee = fraudCase.getAssigneeRef();
            applyStatusChange(fraudCase, command, workflowTime());
            fraudCaseRepository.flush();
            appendAudit(
                    fraudCase,
                    AuditAction.CASE_STATUS_CHANGED,
                    command.reasonCode(),
                    snapshot(beforeStatus, beforeAssignee),
                    snapshot(
                            fraudCase.getCaseStatus(),
                            fraudCase.getAssigneeRef()
                    ),
                    traceId
            );
            return mapper.toResponse(fraudCase, traceId);
        } catch (OptimisticLockingFailureException
                | OptimisticLockException exception) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                    exception
            );
        } catch (DataAccessException exception) {
            throw classifyDataAccess(exception);
        }
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + CASE_WORKFLOW_WRITE + "')")
    public FraudCaseMutationResponse changeAssignee(
            FraudCaseWorkflowCommand.AssigneeChange command,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            FraudCase fraudCase = findCase(command.caseId());
            requireExpectedVersion(fraudCase, command.expectedVersion());
            requireOpenForChange(fraudCase);

            FraudCaseStatus status = fraudCase.getCaseStatus();
            String beforeAssignee = fraudCase.getAssigneeRef();
            applyAssigneeChange(fraudCase, command, workflowTime());
            fraudCaseRepository.flush();
            appendAudit(
                    fraudCase,
                    AuditAction.CASE_ASSIGNEE_CHANGED,
                    command.reasonCode(),
                    snapshot(status, beforeAssignee),
                    snapshot(status, fraudCase.getAssigneeRef()),
                    traceId
            );
            return mapper.toResponse(fraudCase, traceId);
        } catch (OptimisticLockingFailureException
                | OptimisticLockException exception) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                    exception
            );
        } catch (DataAccessException exception) {
            throw classifyDataAccess(exception);
        }
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + CASE_RESOLUTION_WRITE + "')")
    public FraudCaseMutationResponse resolve(
            FraudCaseWorkflowCommand.Resolution command,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            FraudCase fraudCase = findCase(command.caseId());
            requireExpectedVersion(fraudCase, command.expectedVersion());
            requireOpenForChange(fraudCase);
            if (fraudCase.getCaseStatus() != FraudCaseStatus.IN_REVIEW) {
                throw workflow(
                        FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT
                );
            }
            if (fraudCase.getAssigneeRef() == null
                    || fraudCase.getReviewStartedAt() == null) {
                throw workflow(
                        FraudCaseWorkflowException.Reason
                                .INCONSISTENT_CASE_DATA
                );
            }

            FraudCaseFinalDisposition disposition =
                    validator.parseResolutionDisposition(
                            command.finalDisposition()
                    );
            AuditReasonCode reasonCode = validator.parseResolutionReason(
                    command.reasonCode()
            );
            String assigneeRef = fraudCase.getAssigneeRef();
            fraudCase.resolve(disposition, workflowTime());
            fraudCaseRepository.flush();
            appendAudit(
                    fraudCase,
                    AuditAction.CASE_RESOLVED,
                    reasonCode,
                    snapshot(FraudCaseStatus.IN_REVIEW, assigneeRef),
                    resolutionSnapshot(fraudCase),
                    traceId
            );
            return mapper.toResponse(fraudCase, traceId);
        } catch (OptimisticLockingFailureException
                | OptimisticLockException exception) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                    exception
            );
        } catch (DataAccessException exception) {
            throw classifyDataAccess(exception);
        }
    }

    private FraudCase findCase(java.util.UUID caseId) {
        return fraudCaseRepository.findByCaseId(caseId)
                .orElseThrow(FraudCaseNotFoundException::new);
    }

    private void requireExpectedVersion(
            FraudCase fraudCase,
            long expectedVersion
    ) {
        if (fraudCase.getConcurrencyVersion() != expectedVersion) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION
            );
        }
    }

    private void requireOpenForChange(FraudCase fraudCase) {
        if (fraudCase.getCaseStatus() == FraudCaseStatus.CLOSED) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CASE_ALREADY_CLOSED
            );
        }
    }

    private void applyStatusChange(
            FraudCase fraudCase,
            FraudCaseWorkflowCommand.StatusChange command,
            Instant changedAt
    ) {
        FraudCaseStatus current = fraudCase.getCaseStatus();
        FraudCaseStatus target = command.targetStatus();
        if (current == target) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT
            );
        }

        if (current == FraudCaseStatus.OPEN
                && target == FraudCaseStatus.IN_REVIEW) {
            if (!command.assigneeRefPresent()
                    || command.assigneeRef() == null) {
                throw workflow(
                        FraudCaseWorkflowException.Reason.ASSIGNEE_REQUIRED
                );
            }
            validator.requireReason(
                    command.reasonCode(),
                    AuditReasonCode.CASE_REVIEW_STARTED
            );
            fraudCase.startReview(command.assigneeRef(), changedAt);
            return;
        }

        if (current == FraudCaseStatus.IN_REVIEW
                && target
                == FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED) {
            validator.rejectStatusAssigneeCombination(
                    command.assigneeRefPresent()
            );
            validator.requireReason(
                    command.reasonCode(),
                    AuditReasonCode.CASE_ADDITIONAL_INFORMATION_REQUESTED
            );
            fraudCase.requestAdditionalInformation(changedAt);
            return;
        }

        if (current
                == FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED
                && target == FraudCaseStatus.IN_REVIEW) {
            validator.rejectStatusAssigneeCombination(
                    command.assigneeRefPresent()
            );
            if (fraudCase.getAssigneeRef() == null) {
                throw workflow(
                        FraudCaseWorkflowException.Reason.ASSIGNEE_REQUIRED
                );
            }
            validator.requireReason(
                    command.reasonCode(),
                    AuditReasonCode.CASE_REVIEW_RESUMED
            );
            fraudCase.resumeReview(changedAt);
            return;
        }

        throw workflow(
                FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT
        );
    }

    private void applyAssigneeChange(
            FraudCase fraudCase,
            FraudCaseWorkflowCommand.AssigneeChange command,
            Instant changedAt
    ) {
        if (fraudCase.getCaseStatus() == FraudCaseStatus.OPEN) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT
            );
        }
        String before = fraudCase.getAssigneeRef();
        String after = command.assigneeRef();
        if (Objects.equals(before, after)) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT
            );
        }
        if (fraudCase.getCaseStatus() == FraudCaseStatus.IN_REVIEW
                && after == null) {
            throw workflow(
                    FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT
            );
        }

        AuditReasonCode expectedReason;
        if (before == null) {
            expectedReason = AuditReasonCode.CASE_ASSIGNEE_ASSIGNED;
        } else if (after == null) {
            expectedReason = AuditReasonCode.CASE_ASSIGNEE_RELEASED;
        } else {
            expectedReason = AuditReasonCode.CASE_ASSIGNEE_CHANGED;
        }
        validator.requireReason(command.reasonCode(), expectedReason);
        fraudCase.changeAssignee(after, changedAt);
    }

    private void appendAudit(
            FraudCase fraudCase,
            AuditAction action,
            AuditReasonCode reasonCode,
            ObjectNode before,
            ObjectNode after,
            String traceId
    ) {
        auditLogService.append(new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                action,
                reasonCode,
                AuditTargetType.FRAUD_CASE,
                fraudCase.getCaseId(),
                null,
                fraudCase.getCaseId(),
                traceId,
                before,
                after,
                objectMapper.createObjectNode()
        ));
    }

    private ObjectNode snapshot(
            FraudCaseStatus caseStatus,
            String assigneeRef
    ) {
        ObjectNode snapshot = objectMapper.createObjectNode()
                .put("caseStatus", caseStatus.name());
        if (assigneeRef != null) {
            snapshot.put("assigneeRef", assigneeRef);
        }
        return snapshot;
    }

    private ObjectNode resolutionSnapshot(FraudCase fraudCase) {
        return objectMapper.createObjectNode()
                .put("caseStatus", fraudCase.getCaseStatus().name())
                .put(
                        "finalDisposition",
                        fraudCase.getFinalDisposition().name()
                )
                .put("assigneeRef", fraudCase.getAssigneeRef());
    }

    private Instant workflowTime() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private RuntimeException classifyDataAccess(
            DataAccessException exception
    ) {
        if (hasCause(exception, OptimisticLockingFailureException.class)) {
            return workflow(
                    FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                    exception
            );
        }
        if (hasCause(exception, QueryTimeoutException.class)) {
            return workflow(
                    FraudCaseWorkflowException.Reason.DEPENDENCY_TIMEOUT,
                    exception
            );
        }
        if (hasCause(exception, TransientDataAccessResourceException.class)
                || hasCause(
                exception,
                DataAccessResourceFailureException.class
        )) {
            return workflow(
                    FraudCaseWorkflowException.Reason.DEPENDENCY_UNAVAILABLE,
                    exception
            );
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

    private FraudCaseWorkflowException workflow(
            FraudCaseWorkflowException.Reason reason
    ) {
        return new FraudCaseWorkflowException(reason);
    }

    private FraudCaseWorkflowException workflow(
            FraudCaseWorkflowException.Reason reason,
            Throwable cause
    ) {
        return new FraudCaseWorkflowException(reason, cause);
    }
}
