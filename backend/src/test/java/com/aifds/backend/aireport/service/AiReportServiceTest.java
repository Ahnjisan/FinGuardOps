package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.client.AiReportHttpClient;
import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.entity.AiReportRequest;
import com.aifds.backend.aireport.entity.AiReport;
import com.aifds.backend.aireport.entity.AiReportExecution;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.repository.AiReportRepository;
import com.aifds.backend.aireport.repository.AiReportRequestRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.security.principal.CurrentAuditActorProvider;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.outbox.OutboxRepository;
import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.argThat;

class AiReportServiceTest {
    private final FraudCaseRepository cases = mock(FraudCaseRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AiReportInputProjection projection = mock(AiReportInputProjection.class);
    private final AiReportHttpClient client = mock(AiReportHttpClient.class);
    private final AiReportRequestRepository requests = mock(AiReportRequestRepository.class);
    private final AiReportExecutionRepository executions = mock(AiReportExecutionRepository.class);
    private final AiReportRepository reports = mock(AiReportRepository.class);
    private final CurrentAuditActorProvider actors = mock(CurrentAuditActorProvider.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final AiReportService service = new AiReportService(cases, jdbc, projection,
            client, requests, executions, reports, new IdempotencyKeyValidator(), actors,
            entityManager, outbox);
    private final UUID caseId = UUID.randomUUID();

    @Test
    void nullAndLiteralNullRegenerationReasonsHaveDifferentIdempotencyFingerprints() {
        assertNotEquals(AiReportService.fingerprint(caseId,
                new AiReportDtos.CreateRequest(1, null)), AiReportService.fingerprint(caseId,
                new AiReportDtos.CreateRequest(1, "null")));
    }

    @Test
    void missingAndNonReviewCaseNeverCallProvider() {
        var body = new AiReportDtos.CreateRequest(1, null);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(AiReportException.class,
                () -> service.create(caseId, "report-test-001", body, "trace-test-001")).status());
        FraudCase closed = mock(FraudCase.class);
        when(cases.findByCaseId(caseId)).thenReturn(Optional.of(closed));
        when(closed.getId()).thenReturn(10L);
        when(closed.getCaseStatus()).thenReturn(FraudCaseStatus.CLOSED);
        assertEquals("CASE_STATUS_CONFLICT", assertThrows(AiReportException.class,
                () -> service.create(caseId, "report-test-001", body, "trace-test-001")).code());
        verifyNoInteractions(client, projection);
    }

    @Test
    void sameKeyWithDifferentBodyIsConflictBeforeProjectionOrProvider() {
        FraudCase fraudCase = mock(FraudCase.class);
        when(cases.findByCaseId(caseId)).thenReturn(Optional.of(fraudCase));
        when(fraudCase.getId()).thenReturn(10L);
        when(requests.byKey(10L, "report-test-001")).thenReturn(Optional.of(new AiReportRequest(
                1L, UUID.randomUUID(), 10L, null, null, "report-test-001", "wrong-fingerprint",
                1, "prompt", "local-opaque", AiReportStatus.FAILED, false, false,
                "trace-test-001", Instant.now())));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AiReportException.class,
                () -> service.create(caseId, "report-test-001", new AiReportDtos.CreateRequest(1, null),
                        "trace-test-001")).code());
        verifyNoInteractions(client, projection);
    }

    @Test
    void exactVersionCannotReuseAnotherAdoptedDetection() {
        FraudCase fraudCase = mock(FraudCase.class);
        when(cases.findByCaseId(caseId)).thenReturn(Optional.of(fraudCase));
        when(fraudCase.getId()).thenReturn(10L);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
        var input = new AiReportInputProjection.Projection(77L, null);
        when(projection.project(eq(fraudCase), eq(1), eq("trace-test-001"))).thenReturn(input);
        when(client.identity()).thenReturn(new AiReportDtos.ModelIdentity("local-opaque", "prompt-1"));
        when(reports.exact(10L, 1, "prompt-1", "local-opaque"))
                .thenReturn(Optional.of(new AiReport(1L, UUID.randomUUID(), 44L)));
        when(jdbc.queryForObject(eq("SELECT detection_result_id FROM ai_report_execution WHERE id=?"),
                eq(Long.class), eq(44L))).thenReturn(88L);
        assertEquals("STATE_TRANSITION_NOT_ALLOWED", assertThrows(AiReportException.class,
                () -> service.create(caseId, "report-test-001", new AiReportDtos.CreateRequest(1, null),
                        "trace-test-001")).code());
        when(reports.exact(10L, 1, "prompt-1", "local-opaque")).thenReturn(Optional.empty());
        when(executions.active(10L, 1, "prompt-1", "local-opaque"))
                .thenReturn(Optional.of(new AiReportExecution(45L, UUID.randomUUID(), 10L,
                        88L, 1, "prompt-1", "local-opaque", AiReportStatus.PENDING)));
        assertEquals("STATE_TRANSITION_NOT_ALLOWED", assertThrows(AiReportException.class,
                () -> service.create(caseId, "report-test-002", new AiReportDtos.CreateRequest(1, null),
                        "trace-test-001")).code());
    }

    @Test
    void newExecutionWritesOneOutboxButSharedAndReplayDoNot() {
        FraudCase fraudCase = mock(FraudCase.class);
        when(cases.findByCaseId(caseId)).thenReturn(Optional.of(fraudCase));
        when(fraudCase.getId()).thenReturn(10L);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
        when(projection.project(eq(fraudCase), eq(1), eq("trace-test-001")))
                .thenReturn(new AiReportInputProjection.Projection(77L, null));
        when(client.identity()).thenReturn(new AiReportDtos.ModelIdentity("model-1", "prompt-1"));
        UUID executionId = UUID.randomUUID();
        var execution = new AiReportExecution(45L, executionId, 10L, 77L, 1,
                "prompt-1", "model-1", AiReportStatus.PENDING);
        when(executions.insert(10L, 77L, 1, "prompt-1", "model-1")).thenReturn(execution);
        UUID firstId = UUID.randomUUID();
        UUID sharedId = UUID.randomUUID();
        var first = new AiReportRequest(1L, firstId, 10L, 45L, null,
                "first-key", "fingerprint", 1, "prompt-1", "model-1",
                AiReportStatus.PENDING, false, false, "trace-test-001", Instant.now());
        var shared = new AiReportRequest(2L, sharedId, 10L, 45L, null,
                "shared-key", "fingerprint", 1, "prompt-1", "model-1",
                AiReportStatus.PENDING, false, true, "trace-test-001", Instant.now());
        when(actors.currentUserSubject()).thenReturn(UUID.randomUUID());
        when(jdbc.queryForObject(eq("SELECT execution_id FROM ai_report_execution WHERE id=?"),
                eq(UUID.class), eq(45L))).thenReturn(executionId);
        when(jdbc.queryForObject(eq("SELECT case_id FROM fraud_case WHERE id=?"),
                eq(UUID.class), eq(10L))).thenReturn(caseId);
        when(requests.initiators(45L)).thenReturn(java.util.List.of(first));
        when(requests.insert(any(), eq(10L), eq(45L), eq(null), eq("first-key"),
                any(), any(), eq(1), eq("prompt-1"), eq("model-1"),
                eq(AiReportStatus.PENDING), eq(false), eq(false), eq("trace-test-001")))
                .thenReturn(first);
        when(requests.insert(any(), eq(10L), eq(45L), eq(null), eq("shared-key"),
                any(), any(), eq(1), eq("prompt-1"), eq("model-1"),
                eq(AiReportStatus.PENDING), eq(false), eq(true), eq("trace-test-001")))
                .thenReturn(shared);
        var body = new AiReportDtos.CreateRequest(1, null);
        assertEquals(true, service.create(caseId, "first-key", body, "trace-test-001").accepted());
        verify(outbox).insert(argThat(event -> event.executionId().equals(executionId)
                && event.initiatingAiRequestId().equals(firstId)));

        when(executions.active(10L, 1, "prompt-1", "model-1"))
                .thenReturn(Optional.of(execution));
        service.create(caseId, "shared-key", body, "trace-test-001");
        when(requests.byKey(10L, "first-key")).thenReturn(Optional.of(new AiReportRequest(
                1L, firstId, 10L, 45L, null, "first-key",
                AiReportService.fingerprint(caseId, body), 1, "prompt-1", "model-1",
                AiReportStatus.PENDING, false, false, "trace-test-001", Instant.now())));
        service.create(caseId, "first-key", body, "trace-test-001");
        verify(outbox, times(1)).insert(any(AiReportExecutionCreated.class));
    }

    @Test
    void cachedReportCreatesNoOutboxEvent() {
        FraudCase fraudCase = mock(FraudCase.class);
        when(cases.findByCaseId(caseId)).thenReturn(Optional.of(fraudCase));
        when(fraudCase.getId()).thenReturn(10L);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
        when(projection.project(eq(fraudCase), eq(1), eq("trace-test-001")))
                .thenReturn(new AiReportInputProjection.Projection(77L, null));
        when(client.identity()).thenReturn(new AiReportDtos.ModelIdentity("model-1", "prompt-1"));
        var cached = new AiReport(11L, UUID.randomUUID(), 45L);
        when(reports.exact(10L, 1, "prompt-1", "model-1"))
                .thenReturn(Optional.of(cached));
        when(jdbc.queryForObject(eq("SELECT detection_result_id FROM ai_report_execution WHERE id=?"),
                eq(Long.class), eq(45L))).thenReturn(77L);
        AiReportDtos.Report report = mock(AiReportDtos.Report.class);
        when(report.reportStatus()).thenReturn("COMPLETED");
        when(reports.byPk(11L)).thenReturn(Optional.of(report));
        when(actors.currentUserSubject()).thenReturn(UUID.randomUUID());
        var request = new AiReportRequest(2L, UUID.randomUUID(), 10L, null, 11L,
                "cached-key", "fingerprint", 1, "prompt-1", "model-1",
                AiReportStatus.COMPLETED, true, false, "trace-test-001", Instant.now());
        when(requests.insert(any(), eq(10L), eq(null), eq(11L), eq("cached-key"),
                any(), any(), eq(1), eq("prompt-1"), eq("model-1"),
                eq(AiReportStatus.COMPLETED), eq(true), eq(false), eq("trace-test-001")))
                .thenReturn(request);
        when(jdbc.queryForObject(eq("SELECT case_id FROM fraud_case WHERE id=?"),
                eq(UUID.class), eq(10L))).thenReturn(caseId);
        assertEquals(false, service.create(caseId, "cached-key",
                new AiReportDtos.CreateRequest(1, null), "trace-test-001").accepted());
        verifyNoInteractions(outbox);
    }
}
