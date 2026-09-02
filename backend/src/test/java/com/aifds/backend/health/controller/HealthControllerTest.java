package com.aifds.backend.health.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.health.service.HealthService;
import com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({
        HealthService.class,
        TraceIdFilter.class,
        FinGuardOpsSecurityConfiguration.class
})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsBackendHealth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("backend"))
                .andExpect(jsonPath("$.traceId").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void returnsValidExternalTraceIdInHeaderWithoutChangingBody()
            throws Exception {
        String externalTraceId = "Health.Trace_ID:Ab-01";

        mockMvc.perform(get("/api/health")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                externalTraceId
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        externalTraceId
                ))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("backend"))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    @Test
    void explicitMalformedBearerOnPublicHealthReturns401() throws Exception {
        mockMvc.perform(get("/api/health")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        "WWW-Authenticate",
                        "Bearer realm=\"finguardops-backend\", "
                                + "error=\"invalid_token\""
                ))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }
}
