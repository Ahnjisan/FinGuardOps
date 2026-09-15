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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private static final String UNSAFE_PAGE = "1073741824";
    private static final String UNSAFE_SIZE = "2";

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
    void rejectsUnsafePaginationAsSafe422BeforeRepositoryAccess()
            throws Exception {
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new InvalidDataAccessApiUsageException(
                "Spring Data JPA SQL credential Authorization cookie"
        ));

        assertUnsafePagination(
                "1073741824",
                "2",
                "2147483648"
        );
        assertUnsafePagination(
                "21474837",
                "100",
                "2147483700"
        );

        verify(fraudCaseRepository, never()).findAll(
                any(Specification.class),
                any(Pageable.class)
        );
        verifyNoInteractions(caseTransactionRepository);
    }

    @Test
    void keepsMalformedSortAsSafe400AheadOfUnsafePaginationOffset()
            throws Exception {
        MvcResult result = mockMvc.perform(get(PATH)
                        .queryParam("page", "1073741824")
                        .queryParam("size", "2")
                        .queryParam("sort", "invalid-sort")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("요청 필드를 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("INVALID_SORT_FORMAT"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("sort must use field,direction format"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn();

        verifyNoInteractions(fraudCaseRepository, caseTransactionRepository);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(
                "1073741824",
                "\"2\"",
                "2147483648",
                "invalid-sort",
                "InvalidDataAccessApiUsageException",
                "SQL",
                "credential",
                "Authorization",
                "cookie"
        );
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("unsafePaginationLegacyErrors")
    void keepsEveryLegacyErrorAheadOfUnsafePaginationOffset(
            String scenario,
            LegacyErrorCase errorCase
    ) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH)
                .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID);
        errorCase.parameters().forEach(parameter -> request.queryParam(
                parameter.name(),
                parameter.values()
        ));

        String response = mockMvc.perform(request)
                .andExpect(status().is(errorCase.status()))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("요청 필드를 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(errorCase.field()))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value(errorCase.code()))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value(errorCase.reason()))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(
                "page is too large for the requested size",
                UNSAFE_PAGE,
                "2147483648",
                "InvalidDataAccessApiUsageException",
                "FraudCaseQueryValidator",
                "Spring Data",
                "JPA",
                "SQL",
                "credential",
                "Authorization",
                "cookie"
        );
        verifyNoInteractions(fraudCaseRepository, caseTransactionRepository);
    }

    private static Stream<Arguments> unsafePaginationLegacyErrors() {
        return Stream.of(
                repeated("caseStatus", "OPEN", "CLOSED"),
                repeated("finalDisposition", "NORMAL", "CONFIRMED_FRAUD"),
                repeated("assigneeRef", "analyst_01", "analyst_02"),
                repeated(
                        "createdAtFrom",
                        "2026-08-01T00:00:00Z",
                        "2026-08-02T00:00:00Z"
                ),
                repeated(
                        "createdAtTo",
                        "2026-08-02T00:00:00Z",
                        "2026-08-03T00:00:00Z"
                ),
                repeated(
                        "lastChangedAtFrom",
                        "2026-08-01T00:00:00Z",
                        "2026-08-02T00:00:00Z"
                ),
                repeated(
                        "lastChangedAtTo",
                        "2026-08-02T00:00:00Z",
                        "2026-08-03T00:00:00Z"
                ),
                repeated(
                        "transactionId",
                        CASE_ID,
                        "b0000000-0000-4000-9000-000000000002"
                ),
                repeated("page", UNSAFE_PAGE, "1073741825"),
                repeated("size", UNSAFE_SIZE, "3"),
                repeated(
                        "sort",
                        "lastChangedAt,asc",
                        "lastChangedAt,desc"
                ),
                legacy(
                        "multiple repeated scalars keep the first field",
                        400,
                        "caseStatus",
                        "MULTIPLE_VALUES_NOT_ALLOWED",
                        "caseStatus must be provided at most once",
                        query("caseStatus", "OPEN", "CLOSED"),
                        query(
                                "sort",
                                "lastChangedAt,asc",
                                "lastChangedAt,desc"
                        )
                ),
                legacy(
                        "unsupported caseStatus",
                        400,
                        "caseStatus",
                        "UNSUPPORTED_CASE_STATUS",
                        "caseStatus is not supported",
                        query("caseStatus", "invalid-status")
                ),
                legacy(
                        "unsupported finalDisposition",
                        400,
                        "finalDisposition",
                        "UNSUPPORTED_FINAL_DISPOSITION",
                        "finalDisposition is not supported",
                        query("finalDisposition", "invalid-disposition")
                ),
                invalidAssignee("blank assigneeRef", ""),
                invalidAssignee("whitespace assigneeRef", " "),
                invalidAssignee("padded assigneeRef", " analyst_01 "),
                invalidAssignee("overlong assigneeRef", "x".repeat(129)),
                invalidDatetime("createdAtFrom"),
                invalidDatetime("createdAtTo"),
                invalidDatetime("lastChangedAtFrom"),
                invalidDatetime("lastChangedAtTo"),
                legacy(
                        "reversed createdAt range",
                        422,
                        "createdAtFrom",
                        "INVALID_DATETIME_RANGE",
                        "createdAtFrom must not be after its range end",
                        query("createdAtFrom", "2026-08-02T00:00:00Z"),
                        query("createdAtTo", "2026-08-01T00:00:00Z")
                ),
                legacy(
                        "reversed lastChangedAt range",
                        422,
                        "lastChangedAtFrom",
                        "INVALID_DATETIME_RANGE",
                        "lastChangedAtFrom must not be after its range end",
                        query("lastChangedAtFrom", "2026-08-02T00:00:00Z"),
                        query("lastChangedAtTo", "2026-08-01T00:00:00Z")
                ),
                legacy(
                        "transaction UUID format",
                        400,
                        "transactionId",
                        "INVALID_UUID_FORMAT",
                        "transactionId must use the canonical UUID string format",
                        query("transactionId", "not-a-uuid")
                ),
                legacy(
                        "transaction UUID version",
                        400,
                        "transactionId",
                        "INVALID_UUID_VERSION",
                        "transactionId must be a UUID version 4",
                        query(
                                "transactionId",
                                "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
                        )
                ),
                legacy(
                        "transaction UUID variant",
                        400,
                        "transactionId",
                        "INVALID_UUID_VARIANT",
                        "transactionId must use the RFC 4122 variant",
                        query(
                                "transactionId",
                                "10000000-0000-4000-7000-000000000001"
                        )
                ),
                legacy(
                        "page format",
                        400,
                        "page",
                        "INVALID_PAGE_FORMAT",
                        "page must be an integer",
                        query("page", "not-a-page")
                ),
                legacy(
                        "negative page",
                        422,
                        "page",
                        "PAGE_OUT_OF_RANGE",
                        "page must be zero or greater",
                        query("page", "-1")
                ),
                legacy(
                        "size format",
                        400,
                        "size",
                        "INVALID_SIZE_FORMAT",
                        "size must be an integer",
                        query("size", "not-a-size")
                ),
                legacy(
                        "zero size",
                        422,
                        "size",
                        "SIZE_OUT_OF_RANGE",
                        "size must be between 1 and 100",
                        query("size", "0")
                ),
                legacy(
                        "size above maximum",
                        422,
                        "size",
                        "SIZE_OUT_OF_RANGE",
                        "size must be between 1 and 100",
                        query("size", "101")
                ),
                legacy(
                        "empty sort",
                        400,
                        "sort",
                        "INVALID_SORT_FORMAT",
                        "sort must use field,direction format",
                        query("sort", "")
                ),
                legacy(
                        "unsupported sort field",
                        400,
                        "sort",
                        "UNSUPPORTED_SORT_FIELD",
                        "sort field is not supported",
                        query("sort", "createdAt,desc")
                ),
                legacy(
                        "unsupported sort direction",
                        400,
                        "sort",
                        "UNSUPPORTED_SORT_DIRECTION",
                        "sort direction is not supported",
                        query("sort", "lastChangedAt,DESC")
                )
        ).map(errorCase -> Arguments.of(errorCase.scenario(), errorCase));
    }

    private static LegacyErrorCase repeated(
            String field,
            String first,
            String second
    ) {
        return legacy(
                "repeated " + field,
                400,
                field,
                "MULTIPLE_VALUES_NOT_ALLOWED",
                field + " must be provided at most once",
                query(field, first, second)
        );
    }

    private static LegacyErrorCase invalidAssignee(
            String scenario,
            String value
    ) {
        return legacy(
                scenario,
                400,
                "assigneeRef",
                "INVALID_ASSIGNEE_REF",
                "assigneeRef must be 1 to 128 trimmed characters",
                query("assigneeRef", value)
        );
    }

    private static LegacyErrorCase invalidDatetime(String field) {
        return legacy(
                "invalid " + field + " format",
                400,
                field,
                "INVALID_DATETIME_FORMAT",
                field + " must use UTC ISO-8601 Z notation",
                query(field, "invalid-time")
        );
    }

    private static LegacyErrorCase legacy(
            String scenario,
            int status,
            String field,
            String code,
            String reason,
            QueryParameter... invalidParameters
    ) {
        List<QueryParameter> parameters = new ArrayList<>(
                List.of(invalidParameters)
        );
        if (parameters.stream().noneMatch(parameter ->
                "page".equals(parameter.name()))) {
            parameters.add(query("page", UNSAFE_PAGE));
        }
        if (parameters.stream().noneMatch(parameter ->
                "size".equals(parameter.name()))) {
            parameters.add(query("size", UNSAFE_SIZE));
        }
        return new LegacyErrorCase(
                scenario,
                status,
                field,
                code,
                reason,
                List.copyOf(parameters)
        );
    }

    private static QueryParameter query(String name, String... values) {
        return new QueryParameter(name, values);
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

    private void assertUnsafePagination(
            String page,
            String size,
            String offset
    ) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH)
                .queryParam("caseStatus", "OPEN")
                .queryParam("assigneeRef", "opaque_internal_filter")
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParam("sort", "lastChangedAt,asc")
                .queryParam("credential", "credential_raw_offset_test")
                .queryParam("authorization", "authorization_raw_offset_test")
                .queryParam("cookie", "cookie_raw_offset_test")
                .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID);

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("요청 필드를 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("PAGE_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value(
                        "page is too large for the requested size"
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(
                page,
                size,
                offset,
                "OPEN",
                "opaque_internal_filter",
                "lastChangedAt,asc",
                "InvalidDataAccessApiUsageException",
                "FraudCaseQueryValidator",
                "PageableUtils",
                "Spring Data",
                "JPA",
                "SQL",
                "credential",
                "credential_raw_offset_test",
                "authorization_raw_offset_test",
                "cookie_raw_offset_test",
                "Authorization",
                "cookie"
        );
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

    private record LegacyErrorCase(
            String scenario,
            int status,
            String field,
            String code,
            String reason,
            List<QueryParameter> parameters
    ) {
    }

    private record QueryParameter(String name, String[] values) {

        private QueryParameter {
            values = values.clone();
        }

        @Override
        public String[] values() {
            return values.clone();
        }
    }
}
