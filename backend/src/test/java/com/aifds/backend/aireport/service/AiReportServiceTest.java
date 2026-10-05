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
    private final AiReportService service = new AiReportService(cases, jdbc, projection,
            client, requests, executions, reports, new IdempotencyKeyValidator(), actors,
            entityManager);
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
}
