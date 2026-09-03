package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.service.FraudCaseQueryMapper;
import com.aifds.backend.fraudcase.service.FraudCaseQueryService;
import com.aifds.backend.fraudcase.validation.FraudCaseQueryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCaseQueryController.class)
@org.springframework.security.test.context.support.WithMockUser(
        authorities = "case:read"
)
@Import({
        com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        FraudCaseQueryService.class,
        FraudCaseQueryMapper.class,
        FraudCaseQueryValidator.class
})
class FraudCaseQueryControllerTest {

    private static final String PATH = "/api/v1/cases";
    private static final String CASE_ID =
            "a0000000-0000-4000-9000-000000000001";
    private static final String UPPERCASE_UUID =
            "A0000000-0000-4000-9000-000000000001";
    private static final String TRACE_ID = "trace_case_controller_01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FraudCaseRepository fraudCaseRepository;

    @MockitoBean
    private CaseTransactionRepository caseTransactionRepository;

    @BeforeEach
    void resetRepositories() {
        reset(fraudCaseRepository, caseTransactionRepository);
    }

    @Test
    void returnsExactListContractWithDefaultsAndExplicitNulls()
            throws Exception {
        FraudCase fraudCase = fraudCase();
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(fraudCase),
                PageRequest.of(0, 20),
                1
        ));
        CaseTransactionRepository.FraudCaseTransactionCount listCount =
                count(1L, 2L);
        when(caseTransactionRepository.countByFraudCasePks(anyCollection()))
                .thenReturn(List.of(listCount));

        MvcResult result = mockMvc.perform(get(PATH)
                        .queryParam("transactionId", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.content[0].caseId").value(CASE_ID))
                .andExpect(jsonPath("$.content[0].finalDisposition")
                        .value(nullValue()))
                .andExpect(jsonPath("$.content[0].assigneeRef")
                        .value(nullValue()))
                .andExpect(jsonPath("$.content[0].relatedTransactionCount")
                        .value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andReturn();

        JsonNode body = responseBody(result);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "content", "page", "traceId"
        );
        assertThat(fieldNames(body.get("content").get(0)))
                .containsExactlyInAnyOrder(
                        "caseId", "caseStatus", "finalDisposition",
                        "assigneeRef", "relatedTransactionCount",
                        "createdAt", "lastChangedAt"
                );
        assertExcluded(body.toString());
    }

    @Test
    void returnsExactDetailContractAndSafeNotFound() throws Exception {
        FraudCase fraudCase = fraudCase();
        UUID caseId = UUID.fromString(CASE_ID);
        when(fraudCaseRepository.findByCaseId(caseId))
                .thenReturn(Optional.of(fraudCase));
        CaseTransactionRepository.FraudCaseTransactionCount detailCount =
                count(1L, 1L);
        when(caseTransactionRepository.countByFraudCasePks(List.of(1L)))
                .thenReturn(List.of(detailCount));

        MvcResult result = mockMvc.perform(get(PATH + "/" + CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.caseId").value(CASE_ID))
                .andExpect(jsonPath("$.case.reviewStartedAt")
                        .value(nullValue()))
                .andExpect(jsonPath("$.case.closedAt").value(nullValue()))
                .andExpect(jsonPath("$.case.concurrencyVersion").value(0))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn();

        JsonNode body = responseBody(result);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("case", "traceId");
        assertThat(fieldNames(body.get("case"))).containsExactlyInAnyOrder(
                "caseId", "caseStatus", "finalDisposition", "assigneeRef",
                "relatedTransactionCount", "createdAt", "reviewStartedAt",
                "closedAt", "lastChangedAt", "concurrencyVersion"
        );
        assertExcluded(body.toString());

        reset(fraudCaseRepository, caseTransactionRepository);
        when(fraudCaseRepository.findByCaseId(caseId))
                .thenReturn(Optional.empty());
        mockMvc.perform(get(PATH + "/" + CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("요청한 사건을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void mapsFormatAndRepeatedParametersToSafe400() throws Exception {
        assertValidation("caseStatus", "open", 400);
        assertValidation("finalDisposition", "null", 400);
        assertValidation("assigneeRef", " ", 400);
        assertValidation("createdAtFrom", "not-a-date", 400);
        assertValidation("transactionId", "not-a-uuid", 400);
        assertValidation("page", "one", 400);
        assertValidation("size", "1.5", 400);
        assertValidation("sort", "createdAt,desc", 400);

        for (String parameter : new String[]{
                "caseStatus", "finalDisposition", "assigneeRef",
                "createdAtFrom", "createdAtTo", "lastChangedAtFrom",
                "lastChangedAtTo", "transactionId", "page", "size", "sort"
        }) {
            mockMvc.perform(get(PATH).queryParam(parameter, "x", "y"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void mapsDomainBoundsToSafe422() throws Exception {
        assertValidation("page", "-1", 422);
        assertValidation("size", "0", 422);
        assertValidation("size", "101", 422);
        mockMvc.perform(get(PATH)
                        .queryParam("lastChangedAtFrom", "2026-08-02T00:00:00Z")
                        .queryParam("lastChangedAtTo", "2026-08-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsInvalidCaseIdsWithoutEchoingThem() throws Exception {
        for (String invalid : new String[]{
                "credential-secret-not-a-uuid",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "10000000-0000-4000-7000-000000000001"
        }) {
            String response = mockMvc.perform(get(PATH + "/" + invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(invalid, "credential-secret");
        }
    }

    @Test
    void rejectsUppercaseCaseAndTransactionIdsWithoutLeakingInput()
            throws Exception {
        String detailResponse = mockMvc.perform(get(PATH + "/" + UPPERCASE_UUID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("요청 필드를 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("caseId"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("INVALID_UUID_FORMAT"))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();

        String listResponse = mockMvc.perform(get(PATH)
                        .queryParam("transactionId", UPPERCASE_UUID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("요청 필드를 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("transactionId"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("INVALID_UUID_FORMAT"))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();

        assertThat(detailResponse).doesNotContain(
                UPPERCASE_UUID,
                "IllegalArgumentException",
                "stackTrace"
        );
        assertThat(listResponse).doesNotContain(
                UPPERCASE_UUID,
                "IllegalArgumentException",
                "stackTrace"
        );
    }

    @Test
    void mapsRepositoryFailuresWithoutLeakingSensitiveDetails()
            throws Exception {
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new QueryTimeoutException("SELECT credential FROM secret"));
        assertSafeFailure(503, "DEPENDENCY_TIMEOUT");

        reset(fraudCaseRepository, caseTransactionRepository);
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new DataAccessResourceFailureException(
                "password=secret database unavailable"
        ));
        assertSafeFailure(503, "DEPENDENCY_UNAVAILABLE");

        reset(fraudCaseRepository, caseTransactionRepository);
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new DataIntegrityViolationException(
                "fraud_case.internal_column"
        ));
        assertSafeFailure(500, "INTERNAL_ERROR");
    }

    private void assertValidation(String parameter, String value, int status)
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam(parameter, value)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    private void assertSafeFailure(int expectedStatus, String expectedCode)
            throws Exception {
        String response = mockMvc.perform(get(PATH)
                        .queryParam("assigneeRef", "opaque_operator_ref")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(
                "credential", "password", "secret", "internal_column",
                "opaque_operator_ref", CASE_ID, "stackTrace"
        );
    }

    private FraudCase fraudCase() {
        FraudCase fraudCase = FraudCase.open(
                UUID.fromString(CASE_ID),
                Instant.parse("2026-08-01T00:00:00Z")
        );
        ReflectionTestUtils.setField(fraudCase, "id", 1L);
        return fraudCase;
    }

    private CaseTransactionRepository.FraudCaseTransactionCount count(
            long casePk,
            long transactionCount
    ) {
        CaseTransactionRepository.FraudCaseTransactionCount count = mock(
                CaseTransactionRepository.FraudCaseTransactionCount.class
        );
        when(count.getFraudCasePk()).thenReturn(casePk);
        when(count.getTransactionCount()).thenReturn(transactionCount);
        return count;
    }

    private JsonNode responseBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void assertExcluded(String json) {
        assertThat(json).doesNotContain(
                "\"id\"", "fraudCaseId", "financialTransactionId",
                "externalCustomerRef", "senderAccountRef",
                "recipientAccountRef", "deviceRef", "provider",
                "snapshot", "payload", "credential", "stackTrace",
                "representativeRiskLevel", "representativeReason",
                "noteCount", "auditSummary"
        );
    }
}
