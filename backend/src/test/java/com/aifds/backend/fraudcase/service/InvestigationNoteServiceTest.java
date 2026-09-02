package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.fraudcase.command.FraudCaseNoteCommand;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.entity.InvestigationNote;
import com.aifds.backend.fraudcase.exception.InvestigationNoteException;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.repository.InvestigationNoteRepository;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InvestigationNoteServiceTest {

    private final FraudCaseRepository cases = mock(FraudCaseRepository.class);
    private final InvestigationNoteRepository notes = mock(InvestigationNoteRepository.class);
    private final InvestigationNoteValidator validator = mock(InvestigationNoteValidator.class);
    private final InvestigationNoteMapper mapper = mock(InvestigationNoteMapper.class);
    private final AuditLogPersistenceService audits = mock(AuditLogPersistenceService.class);
    private final FraudCase fraudCase = mock(FraudCase.class);
    private final Instant sameTime = Instant.parse("2026-09-02T00:00:00.123456Z");
    private InvestigationNoteService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        service = new InvestigationNoteService(
                cases, notes, validator, mapper, audits, new ObjectMapper(),
                Clock.fixed(sameTime, ZoneOffset.UTC)
        );
        when(cases.findByCaseId(caseId)).thenReturn(Optional.of(fraudCase));
        when(fraudCase.getId()).thenReturn(5L);
        when(fraudCase.getCaseId()).thenReturn(caseId);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
        when(fraudCase.getConcurrencyVersion()).thenReturn(6L);
        when(fraudCase.getLastChangedAt()).thenReturn(sameTime);
        when(notes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void flushesParentThenNoteThenExactAuditAndAdvancesEqualClockByOneMicrosecond() {
        service.create(new FraudCaseNoteCommand.Create(caseId, "  memo\r\n", 6), "trace_note_001");

        verify(fraudCase).recordInvestigationNoteActivity(
                sameTime.plusNanos(1_000)
        );
        ArgumentCaptor<InvestigationNote> note = ArgumentCaptor.forClass(InvestigationNote.class);
        verify(notes).saveAndFlush(note.capture());
        assertThat(note.getValue().getCreatedAt()).isEqualTo(sameTime.plusNanos(1_000));
        assertThat(note.getValue().getContent()).isEqualTo("  memo\r\n");
        ArgumentCaptor<AuditLogDraft> audit = ArgumentCaptor.forClass(AuditLogDraft.class);
        verify(audits).append(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditAction.CASE_NOTE_CREATED);
        assertThat(audit.getValue().reasonCode())
                .isEqualTo(AuditReasonCode.CASE_INVESTIGATION_NOTE_ADDED);
        assertThat(audit.getValue().actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(audit.getValue().actorId()).isEqualTo(AuditLog.SYSTEM_ACTOR_ID);
        assertThat(audit.getValue().targetType()).isEqualTo(AuditTargetType.FRAUD_CASE);
        assertThat(audit.getValue().targetId()).isEqualTo(caseId);
        assertThat(audit.getValue().caseId()).isEqualTo(caseId);
        assertThat(audit.getValue().transactionId()).isNull();
        assertThat(audit.getValue().traceId()).isEqualTo("trace_note_001");
        assertThat(audit.getValue().beforeValueSummary()).isNull();
        assertThat(audit.getValue().afterValueSummary()).isNull();
        assertThat(audit.getValue().metadata().fieldNames()).toIterable()
                .containsExactly("noteId");
        assertThat(audit.getValue().metadata().toString()).doesNotContain("memo");
        InOrder order = inOrder(fraudCase, cases, notes, audits);
        order.verify(fraudCase).recordInvestigationNoteActivity(any());
        order.verify(cases).flush();
        order.verify(notes).saveAndFlush(any());
        order.verify(audits).append(any());
    }

    @Test
    void allowsAdditionalInformationRequiredState() {
        when(fraudCase.getCaseStatus())
                .thenReturn(FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED);

        service.create(new FraudCaseNoteCommand.Create(caseId, "memo", 6),
                "trace_note_003");

        verify(notes).saveAndFlush(any());
        verify(audits).append(any());
    }

    @Test
    void staleVersionWinsOverStateAndProducesNoSideEffects() {
        when(fraudCase.getConcurrencyVersion()).thenReturn(7L);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.CLOSED);

        assertThatThrownBy(() -> service.create(
                new FraudCaseNoteCommand.Create(caseId, "secret memo", 6), "trace_note_001"
        )).isInstanceOf(InvestigationNoteException.class)
                .extracting("reason")
                .isEqualTo(InvestigationNoteException.Reason.CONCURRENT_MODIFICATION);
        verifyNoInteractions(validator, notes, audits, mapper);
    }

    @Test
    void rejectsOpenAndClosedBeforeContentValidationAndClassifiesAuditFailureInternal() {
        for (FraudCaseStatus status : List.of(FraudCaseStatus.OPEN, FraudCaseStatus.CLOSED)) {
            when(fraudCase.getCaseStatus()).thenReturn(status);
            assertThatThrownBy(() -> service.create(
                    new FraudCaseNoteCommand.Create(caseId, "secret memo", 6),
                    "trace_note_001"
            )).isInstanceOf(InvestigationNoteException.class)
                    .extracting("reason")
                    .isEqualTo(InvestigationNoteException.Reason.NOTE_NOT_ALLOWED);
            verifyNoInteractions(validator, notes, audits, mapper);
        }

        reset(validator, notes, audits, mapper);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
        when(notes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException("audit rejected"))
                .when(audits).append(any());
        assertThatThrownBy(() -> service.create(
                new FraudCaseNoteCommand.Create(caseId, "secret memo", 6), "trace_note_001"
        )).isInstanceOf(InvestigationNoteException.class)
                .extracting("reason").isEqualTo(InvestigationNoteException.Reason.INTERNAL_FAILURE);
    }
}
