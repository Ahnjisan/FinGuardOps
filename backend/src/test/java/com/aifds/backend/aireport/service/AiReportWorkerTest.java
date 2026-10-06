package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.client.AiReportHttpClient;
import com.aifds.backend.aireport.config.AiReportProperties;
import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.entity.AiReport;
import com.aifds.backend.aireport.entity.AiReportExecution;
import com.aifds.backend.aireport.entity.AiReportRequest;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.repository.AiReportRepository;
import com.aifds.backend.aireport.repository.AiReportRequestRepository;
import com.aifds.backend.aireport.repository.ProviderCallAttemptRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiReportWorkerTest {
    private final AiReportExecutionRepository executions = mock(AiReportExecutionRepository.class);
    private final AiReportRequestRepository requests = mock(AiReportRequestRepository.class);
    private final AiReportRepository reports = mock(AiReportRepository.class);
    private final ProviderCallAttemptRepository attempts = mock(ProviderCallAttemptRepository.class);
    private final FraudCaseRepository cases = mock(FraudCaseRepository.class);
    private final AiReportInputProjection projection = mock(AiReportInputProjection.class);
    private final AiReportHttpClient client = mock(AiReportHttpClient.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AiReportExecution row = new AiReportExecution(7, UUID.randomUUID(), 3, 5,
            1, "prompt-1", "model-1", AiReportStatus.GENERATING);
    private final AiReportDtos.GenerationRequest input = new AiReportDtos.GenerationRequest(
            UUID.randomUUID(), 1, "HIGH", 80, "rules-1",
            List.of(new AiReportDtos.RuleEvidence("RULE_A", "1", "REASON_A", 20)), "trace-test-001");

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> result = mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(bean);
        return result;
    }

    private AiReportWorker worker() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(executions.claim(300)).thenReturn(Optional.of(row));
        when(executions.stillGenerating(row.id())).thenReturn(true);
        FraudCase fraudCase = mock(FraudCase.class);
        when(cases.findById(row.casePk())).thenReturn(Optional.of(fraudCase));
        AiReportRequest initiator = new AiReportRequest(1, UUID.randomUUID(), row.casePk(),
                row.id(), null, "key", "a".repeat(64), 1, "prompt-1", "model-1",
                AiReportStatus.GENERATING, false, false, "trace-test-001", Instant.now());
        when(requests.initiators(row.id())).thenReturn(List.of(initiator));
        when(projection.project(fraudCase, 1, "trace-test-001"))
                .thenReturn(new AiReportInputProjection.Projection(row.detectionPk(), input));
        return new AiReportWorker(provider(manager), provider(mock(JdbcTemplate.class)),
                new AiReportProperties.Values("http://localhost:8000", 1000, 300),
                provider(executions), provider(requests), provider(reports), provider(attempts),
                provider(cases), provider(projection), provider(client));
    }

    @Test
    void storesDistinctPostAcceptanceTransportFailuresWithoutAnAttempt() {
        for (String code : List.of("FASTAPI_CONNECTION_FAILED", "FASTAPI_TIMEOUT")) {
            AiReportWorker worker = worker();
            doThrow(new AiReportHttpClient.GenerationFailure(code)).when(client).generate(input);
            worker.tick();
            verify(executions).complete(row.id(), AiReportStatus.FAILED, code, null);
            verify(requests).fail(row.id());
            verifyNoInteractions(attempts);
            clearInvocations(executions, requests, attempts, client);
        }
    }

    @Test
    void rejectsMalformedFastApiResultBeforeSavingAttempts() {
        AiReportWorker worker = worker();
        when(client.generate(input)).thenReturn(new AiReportDtos.GenerationResult(
                "COMPLETED", "LLM", null, null, null, "model-1", "prompt-1", List.of()));
        worker.tick();
        verify(executions).complete(row.id(), AiReportStatus.FAILED, "FASTAPI_RESPONSE_INVALID", null);
        verifyNoInteractions(attempts);
    }

    @Test
    void keepsFallbackTriggerSeparateFromFinalFailure() {
        AiReportWorker worker = worker();
        var failed = new AiReportDtos.GenerationResult("FAILED", null, null,
                "TEMPLATE_FALLBACK_FAILED", "LLM_TIMEOUT", "model-1", "prompt-1",
                List.of(new AiReportDtos.Attempt("OLLAMA_LOCAL", null, null,
                        null, null, 45000, "TIMEOUT")));
        when(client.generate(input)).thenReturn(failed);
        worker.tick();
        verify(attempts).insert(eq(row.id()), eq(1), any());
        verify(executions).complete(row.id(), AiReportStatus.FAILED,
                "TEMPLATE_FALLBACK_FAILED", "LLM_TIMEOUT");
    }

    @Test
    void doesNotClaimPersistenceFailureWasSavedWhenTransactionFails() {
        AiReportWorker worker = worker();
        var generated = new AiReportDtos.GenerationResult("FALLBACK_COMPLETED",
                "TEMPLATE_FALLBACK", new AiReportDtos.Content("summary",
                List.of(new AiReportDtos.KeyReason("REASON_A", "reason")), List.of("check")),
                null, "LLM_TIMEOUT", "model-1", "prompt-1", List.of());
        when(client.generate(input)).thenReturn(generated);
        when(reports.insert(eq(row.casePk()), eq(row.id()), eq(1), eq("prompt-1"),
                eq("model-1"), eq(generated), any())).thenThrow(new IllegalStateException("write failed"));
        assertThrows(IllegalStateException.class, worker::tick);
        verify(executions, never()).complete(eq(row.id()), eq(AiReportStatus.FAILED),
                eq("RESULT_PERSISTENCE_FAILED"), any());
        verify(requests, never()).fail(row.id());
    }
}
