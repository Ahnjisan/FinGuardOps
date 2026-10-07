package com.aifds.backend.outbox;

import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.security.principal.CurrentAuditActorProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxRecoveryControllerTest {
    private final OutboxRecoveryService service = mock(OutboxRecoveryService.class);
    private final CurrentAuditActorProvider actors = mock(CurrentAuditActorProvider.class);
    private final OutboxRecoveryController controller = new OutboxRecoveryController(service, actors);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void refusesExtraFieldsBeforeRequeue() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var body = json.readTree("""
                {"executionId":"%s","observedStatus":"BLOCKED","payload":"secret"}
                """.formatted(executionId));
        assertEquals(400, assertThrows(AiReportException.class,
                () -> controller.requeue(eventId.toString(), body, "trace-test-001"))
                .status().value());
        verifyNoInteractions(service, actors);
    }

    @Test
    void mapsPostgresNowaitContentionToConflict() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(actors.currentUserSubject()).thenReturn(UUID.randomUUID());
        when(service.requeue(any(), any(), any(), any(), any()))
                .thenThrow(new UncategorizedSQLException("lock", "SELECT FOR UPDATE NOWAIT",
                        new SQLException("lock unavailable", "55P03")));
        var body = json.readTree("""
                {"executionId":"%s","observedStatus":"BLOCKED"}
                """.formatted(executionId));
        assertEquals(409, assertThrows(AiReportException.class,
                () -> controller.requeue(eventId.toString(), body, "trace-test-001"))
                .status().value());
    }

    @Test
    void mapsObservedStatusMismatchToConflict() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(actors.currentUserSubject()).thenReturn(UUID.randomUUID());
        when(service.requeue(any(), any(), any(), any(), any()))
                .thenThrow(new AiReportException(org.springframework.http.HttpStatus.CONFLICT,
                        "OUTBOX_REQUEUE_NOT_ALLOWED"));
        var body = json.readTree("""
                {"executionId":"%s","observedStatus":"PENDING"}
                """.formatted(executionId));
        assertEquals(409, assertThrows(AiReportException.class,
                () -> controller.requeue(eventId.toString(), body, "trace-test-001"))
                .status().value());
        verify(service).requeue(eq(eventId), eq(executionId), eq("PENDING"), any(),
                eq("trace-test-001"));
    }
}
