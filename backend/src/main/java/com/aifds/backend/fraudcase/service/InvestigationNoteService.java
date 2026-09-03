package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.fraudcase.command.FraudCaseNoteCommand;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.entity.InvestigationNote;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.InvestigationNoteException;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.repository.InvestigationNoteRepository;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_WRITE;

@Service
public class InvestigationNoteService {

    private final FraudCaseRepository fraudCaseRepository;
    private final InvestigationNoteRepository noteRepository;
    private final InvestigationNoteValidator validator;
    private final InvestigationNoteMapper mapper;
    private final AuditLogPersistenceService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InvestigationNoteService(
            FraudCaseRepository fraudCaseRepository,
            InvestigationNoteRepository noteRepository,
            InvestigationNoteValidator validator,
            InvestigationNoteMapper mapper,
            AuditLogPersistenceService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.noteRepository = noteRepository;
        this.validator = validator;
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + CASE_NOTE_WRITE + "')")
    public InvestigationNoteCreateResponse create(
            FraudCaseNoteCommand.Create command,
            String traceId
    ) {
        try {
            FraudCase fraudCase = findCase(command.caseId());
            if (fraudCase.getConcurrencyVersion() != command.expectedVersion()) {
                throw failure(InvestigationNoteException.Reason.CONCURRENT_MODIFICATION, null);
            }
            if (fraudCase.getCaseStatus() != FraudCaseStatus.IN_REVIEW
                    && fraudCase.getCaseStatus() != FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED) {
                throw failure(InvestigationNoteException.Reason.NOTE_NOT_ALLOWED, null);
            }
            validator.validateContent(command.content());
            Instant activityTime = activityTime(fraudCase.getLastChangedAt());
            fraudCase.recordInvestigationNoteActivity(activityTime);
            fraudCaseRepository.flush();

            InvestigationNote note = InvestigationNote.systemAuthored(
                    UUID.randomUUID(), fraudCase.getId(), command.content(), activityTime
            );
            noteRepository.saveAndFlush(note);
            var metadata = objectMapper.createObjectNode();
            metadata.put("noteId", note.getNoteId().toString());
            auditService.append(new AuditLogDraft(
                    AuditActorType.SYSTEM,
                    AuditLog.SYSTEM_ACTOR_ID,
                    AuditAction.CASE_NOTE_CREATED,
                    AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED,
                    AuditTargetType.FRAUD_CASE,
                    fraudCase.getCaseId(),
                    null,
                    fraudCase.getCaseId(),
                    traceId,
                    null,
                    null,
                    metadata
            ));
            return mapper.toCreateResponse(note, fraudCase, traceId);
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw failure(InvestigationNoteException.Reason.CONCURRENT_MODIFICATION, exception);
        } catch (DataAccessException exception) {
            throw classify(exception);
        }
    }

    @Transactional(readOnly = true)
    public InvestigationNoteListResponse list(
            FraudCaseNoteCommand.ListQuery query,
            String traceId
    ) {
        try {
            FraudCase fraudCase = findCase(query.caseId());
            PageRequest pageable = PageRequest.of(query.page(), query.size());
            Page<InvestigationNote> notes = query.direction() == FraudCaseNoteCommand.Direction.ASC
                    ? noteRepository.findPageAscending(fraudCase.getId(), pageable)
                    : noteRepository.findPageDescending(fraudCase.getId(), pageable);
            return mapper.toListResponse(notes, fraudCase.getCaseId(), traceId);
        } catch (DataAccessException exception) {
            throw classify(exception);
        }
    }

    private FraudCase findCase(UUID caseId) {
        return fraudCaseRepository.findByCaseId(caseId)
                .orElseThrow(FraudCaseNotFoundException::new);
    }

    private Instant activityTime(Instant lastChangedAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(lastChangedAt) ? now : lastChangedAt.plus(1, ChronoUnit.MICROS);
    }

    private InvestigationNoteException classify(DataAccessException exception) {
        if (hasCause(exception, QueryTimeoutException.class)) {
            return failure(InvestigationNoteException.Reason.DEPENDENCY_TIMEOUT, exception);
        }
        if (hasCause(exception, DataAccessResourceFailureException.class)
                || hasCause(exception, TransientDataAccessResourceException.class)) {
            return failure(InvestigationNoteException.Reason.DEPENDENCY_UNAVAILABLE, exception);
        }
        return failure(InvestigationNoteException.Reason.INTERNAL_FAILURE, exception);
    }

    private boolean hasCause(Throwable value, Class<? extends Throwable> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = value;
        while (current != null && seen.add(current)) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private InvestigationNoteException failure(
            InvestigationNoteException.Reason reason,
            Throwable cause
    ) {
        return new InvestigationNoteException(reason, cause);
    }
}
