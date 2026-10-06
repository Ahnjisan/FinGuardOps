package com.aifds.backend.aireport.controller;

import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.service.AiReportService;
import com.aifds.backend.common.trace.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiReportControllerTest {
    private final AiReportService service = mock(AiReportService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiReportController(service)).build();

    @Test
    void acceptsOnlyExactCreateFields() throws Exception {
        UUID caseId = UUID.randomUUID();
        var response = new AiReportDtos.RequestStatus(UUID.randomUUID(), UUID.randomUUID(), false,
                UUID.randomUUID(), null, caseId, 1, "PENDING", null, null, false,
                Instant.now(), null, null, null,
                "/api/v1/cases/" + caseId + "/ai-reports/current",
                "trace-test-001");
        when(service.create(eq(caseId), eq("report-test-001"), any(), eq("trace-test-001")))
                .thenReturn(new AiReportService.CreateOutcome(response, true));
        mvc.perform(post("/api/v1/cases/{caseId}/ai-reports", caseId)
                .requestAttr(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE, "trace-test-001")
                .header("Idempotency-Key", "report-test-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"detectionResultVersion\":1,\"regenerationReason\":null}"))
                .andExpect(status().isAccepted());
        var exception = assertThrows(jakarta.servlet.ServletException.class, () ->
            mvc.perform(post("/api/v1/cases/{caseId}/ai-reports", caseId)
                    .requestAttr(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE, "trace-test-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"detectionResultVersion\":1,\"externalCustomerRef\":\"secret\"}")));
        assertEquals(AiReportException.class, exception.getCause().getClass());
        verify(service, times(1)).create(eq(caseId), eq("report-test-001"), any(), eq("trace-test-001"));
    }
}
