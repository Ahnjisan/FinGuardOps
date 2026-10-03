package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.service.FraudCaseTransactionQueryService;
import com.aifds.backend.fraudcase.validation.FraudCaseTransactionQueryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {FraudCaseQueryController.class,
        com.aifds.backend.transaction.controller.TransactionQueryController.class},
        properties = "finguardops.security.allowed-origins=http://localhost:5173")
@org.springframework.security.test.context.support.WithMockUser(authorities = "case:read")
@Import({com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class, TraceIdFilter.class,
        FraudCaseTransactionQueryService.class, FraudCaseTransactionQueryValidator.class})
class FraudCaseTransactionQueryControllerTest {
    private static final UUID CASE_ID = UUID.fromString("20000000-0000-4000-9000-000000000003");
    private static final UUID TRANSACTION_ID = UUID.fromString("91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003");
    private static final String PATH = "/api/v1/cases/" + CASE_ID + "/transactions";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean FraudCaseRepository cases;
    @MockitoBean CaseTransactionRepository links;
    @MockitoBean com.aifds.backend.fraudcase.service.FraudCaseQueryService otherService;
    @MockitoBean com.aifds.backend.transaction.service.TransactionQueryService transactionService;

    @Test
    @WithAnonymousUser
    void unauthenticatedRequestIs401() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(cases, links);
    }

    @Test
    @WithMockUser(authorities = "rule-version:read")
    void lackingCaseReadDoesNotRevealExistenceOrInputs() throws Exception {
        for (String path : List.of(PATH, PATH + "?page=secret",
                "/api/v1/cases/20000000-0000-4000-9000-000000000099/transactions",
                "/api/v1/cases/invalid/transactions")) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        verifyNoInteractions(cases, links);
    }

    @Test
    void caseReadDoesNotGrantTransactionDetail() throws Exception {
        mvc.perform(get("/api/v1/transactions/" + TRANSACTION_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(transactionService);
    }

    @Test
    @WithAnonymousUser
    void approvedCorsPreflightHasExactGetPath() throws Exception {
        mvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk());
    }

    @Test
    void returnsOnlyPublicIdsAndPageFields() throws Exception {
        FraudCase fraudCase = mock(FraudCase.class);
        when(fraudCase.getId()).thenReturn(7L);
        when(cases.findByCaseId(CASE_ID)).thenReturn(Optional.of(fraudCase));
        when(links.findTransactionIdsByFraudCasePk(eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(TRANSACTION_ID), PageRequest.of(0, 20), 1));
        var result = mvc.perform(get(PATH).header(TraceIdFilter.TRACE_ID_HEADER, "trace_case_tx_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("caseId", "content", "page", "traceId");
        assertThat(fieldNames(body.get("content").get(0))).containsExactly("transactionId");
        assertThat(fieldNames(body.get("page"))).containsExactlyInAnyOrder(
                "number", "size", "totalElements", "totalPages", "first", "last");
    }

    @Test
    void distinguishesMissingCaseFromEmptyRelations() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        FraudCase fraudCase = mock(FraudCase.class);
        when(fraudCase.getId()).thenReturn(7L);
        when(cases.findByCaseId(CASE_ID)).thenReturn(Optional.of(fraudCase));
        when(links.findTransactionIdsByFraudCasePk(eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void validatesQueryAndUuidBeforeDatabase() throws Exception {
        for (String path : List.of(PATH + "?sort=id", PATH + "?page=", PATH + "?page=0&page=1",
                PATH + "?size=1&size=2", PATH + "?other=secret",
                "/api/v1/cases/20000000-0000-1000-9000-000000000003/transactions",
                "/api/v1/cases/20000000-0000-4000-1000-000000000003/transactions",
                "/api/v1/cases/20000000-0000-4000-9000-000000000003X/transactions")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        for (String path : List.of(PATH + "?page=-1", PATH + "?size=0", PATH + "?size=101",
                PATH + "?page=1073741824&size=2")) {
            mvc.perform(get(path)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(cases, links);
    }

    @Test
    void classifiesDatabaseFailures() throws Exception {
        when(cases.findByCaseId(CASE_ID)).thenThrow(new QueryTimeoutException("secret"));
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"));
        reset(cases);
        when(cases.findByCaseId(CASE_ID)).thenThrow(new DataAccessResourceFailureException("secret"));
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    private List<String> fieldNames(JsonNode node) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
