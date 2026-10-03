package com.aifds.backend.detection.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.detection.service.AdoptedDetectionResultQueryService;
import com.aifds.backend.detection.dto.AdoptedDetectionResultResponse;
import com.aifds.backend.detection.dto.AdoptedDetectionResultItemResponse;
import com.aifds.backend.detection.dto.AdoptedRuleEvidenceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdoptedDetectionResultQueryController.class)
@Import({com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class, TraceIdFilter.class})
class AdoptedDetectionResultQueryControllerTest {
    private static final String PATH = "/api/v1/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001/adopted-detection-result";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AdoptedDetectionResultQueryService query;

    @Test
    void requiresBothReadAuthoritiesBeforeQuery() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(user("viewer").authorities(
                () -> "transaction:read"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(user("viewer").authorities(
                () -> "detection:read"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(user("viewer").authorities(
                () -> "case:read"))).andExpect(status().isForbidden());
        verifyNoInteractions(query);
        mvc.perform(get(PATH).with(user("viewer").authorities(
                () -> "transaction:read", () -> "detection:read")))
                .andExpect(status().isOk());
        verify(query).find("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001");
    }

    @Test
    void serializesOnlyTheApprovedProjection() throws Exception {
        UUID transactionId = UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001");
        when(query.find(transactionId.toString())).thenReturn(new AdoptedDetectionResultResponse(
                transactionId, "AVAILABLE", 2, "IN_PROGRESS",
                new AdoptedDetectionResultItemResponse(
                        UUID.fromString("7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101"),
                        1, "HIGH", 55, Instant.parse("2026-07-23T01:15:32Z"),
                        "rule-v1", "scoring-policy-v1", List.of(
                        new AdoptedRuleEvidenceResponse("R001", "1",
                                "TRANSFER_ABSOLUTE_HIGH_AMOUNT", 15))
                )));
        var response = mvc.perform(get(PATH).with(user("viewer").authorities(
                () -> "transaction:read", () -> "detection:read")))
                .andExpect(status().isOk()).andReturn().getResponse();
        var root = objectMapper.readTree(response.getContentAsString());
        assertThat(root.size()).isEqualTo(5);
        assertThat(root.get("adoptedResult").size()).isEqualTo(8);
        assertThat(root.get("adoptedResult").get("ruleEvidence").get(0).size()).isEqualTo(4);
        for (String forbidden : List.of("observationSummary", "displayDescription", "failureCode",
                "traceId", "providerResponse", "token", "financialTransactionId")) {
            assertThat(response.getContentAsString()).doesNotContain(forbidden);
        }
    }

    @Test
    void rejectsAnyQueryWithoutCallingTheService() throws Exception {
        mvc.perform(get(PATH + "?unused=1").with(user("viewer").authorities(
                () -> "transaction:read", () -> "detection:read")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(query);
    }
}
