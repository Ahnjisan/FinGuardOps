package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.repository.AuditLogRepository;
import com.aifds.backend.audit.service.AuditMetadataPolicy;
import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.service.FraudCaseAuditLogMapper;
import com.aifds.backend.fraudcase.service.FraudCaseAuditLogService;
import com.aifds.backend.fraudcase.validation.FraudCaseAuditLogQueryValidator;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCaseAuditLogController.class)
@org.springframework.security.test.context.support.WithMockUser(
        authorities = "case-audit:read"
)
@Import({
        com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        FraudCaseAuditLogService.class,
        FraudCaseAuditLogMapper.class,
        FraudCaseAuditLogQueryValidator.class,
        AuditMetadataPolicy.class
})
class FraudCaseAuditLogControllerTest {

    private static final UUID CASE_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000001"
    );
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "20000000-0000-4000-9000-000000000001"
    );
    private static final String TRACE_ID = "trace_audit_request_01";
    private static final String PATH =
            "/api/v1/cases/" + CASE_ID + "/audit-logs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FraudCaseRepository fraudCaseRepository;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void resetRepositories() {
        reset(fraudCaseRepository, auditLogRepository);
    }

    @Test
    void returnsExactProjectionExplicitNullsAndCurrentTrace() throws Exception {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(caseCreated()), PageRequest.of(0, 20), 1
        ));

        MvcResult result = mockMvc.perform(get(PATH)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER, TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.caseId").value(CASE_ID.toString()))
                .andExpect(jsonPath("$.content[0].action")
                        .value("CASE_CREATED"))
                .andExpect(jsonPath("$.content[0].beforeSummary")
                        .value(nullValue()))
                .andExpect(jsonPath("$.content[0].afterSummary.caseStatus")
                        .value("OPEN"))
                .andExpect(jsonPath("$.content[0].metadata").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andReturn();

        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
        assertThat(fields(body)).containsExactlyInAnyOrder(
                "caseId", "content", "page", "traceId"
        );
        assertThat(fields(body.get("content").get(0)))
                .containsExactlyInAnyOrder(
                        "action", "reasonCode", "actorType", "changedAt",
                        "beforeSummary", "afterSummary", "metadata"
                );
        assertExcluded(result.getResponse().getContentAsString());
    }

    @Test
    void returnsEmptyContentForExistingCaseWithoutAudits() throws Exception {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void mapsFormatDomainNotFoundDependencyAndInternalErrors() throws Exception {
        assertSafeError(
                "/api/v1/cases/credential-secret/audit-logs",
                400,
                "VALIDATION_ERROR"
        );
        assertSafeError(PATH + "?unknown=credential-secret", 400,
                "VALIDATION_ERROR");
        assertSafeError(PATH + "?page=0&page=1", 400,
                "VALIDATION_ERROR");
        assertSafeError(PATH + "?size=101", 422, "VALIDATION_ERROR");

        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(false);
        assertSafeError(PATH, 404, "RESOURCE_NOT_FOUND");

        reset(fraudCaseRepository, auditLogRepository);
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenThrow(
                new QueryTimeoutException("SELECT credential FROM secret")
        );
        assertSafeError(PATH, 503, "DEPENDENCY_TIMEOUT");

        reset(fraudCaseRepository, auditLogRepository);
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenThrow(
                new DataAccessResourceFailureException("password=secret")
        );
        assertSafeError(PATH, 503, "DEPENDENCY_UNAVAILABLE");

        reset(fraudCaseRepository, auditLogRepository);
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenThrow(
                new DataIntegrityViolationException("schema.raw_column")
        );
        assertSafeError(PATH, 500, "INTERNAL_ERROR");
    }

    @Test
    void rejectsUnsafePaginationAsNonReflective422BeforeRepositories()
            throws Exception {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenThrow(new InvalidDataAccessApiUsageException(
                "Spring Data JPA SQL credential Authorization cookie"
        ));

        MvcResult result = mockMvc.perform(get(PATH)
                        .queryParam("page", "1073741824")
                        .queryParam("size", "2")
                        .queryParam("sort", "changedAt,asc")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .header("Cookie", "session=credential-cookie"))
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );

        assertAll(
                () -> assertThat(result.getResponse().getStatus())
                        .isEqualTo(422),
                () -> assertThat(result.getResponse().getHeader(
                        TraceIdFilter.TRACE_ID_HEADER
                )).isEqualTo(TRACE_ID),
                () -> assertThat(body.path("code").asText())
                        .isEqualTo("VALIDATION_ERROR"),
                () -> assertThat(body.path("message").asText())
                        .isEqualTo("요청 필드를 확인해 주세요."),
                () -> assertThat(body.path("fieldErrors").size()).isEqualTo(1),
                () -> assertThat(body.at("/fieldErrors/0/field").asText())
                        .isEqualTo("page"),
                () -> assertThat(body.at("/fieldErrors/0/code").asText())
                        .isEqualTo("PAGE_OUT_OF_RANGE"),
                () -> assertThat(body.at("/fieldErrors/0/reason").asText())
                        .isEqualTo(
                                "page is too large for the requested size"
                        ),
                () -> assertThat(body.path("traceId").asText())
                        .isEqualTo(TRACE_ID),
                () -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(
                                "1073741824",
                                "\"2\"",
                                "2147483648",
                                "changedAt,asc",
                                CASE_ID.toString(),
                                "FraudCaseValidationException",
                                "InvalidDataAccessApiUsageException",
                                "FraudCaseAuditLogQueryValidator",
                                "PageableUtils",
                                "Spring Data",
                                "JPA",
                                "SQL",
                                "credential",
                                "cookie"
                        ),
                () -> verifyNoInteractions(
                        fraudCaseRepository, auditLogRepository
                )
        );
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("unsafePaginationLegacyErrors")
    void preservesLegacyListValidationContractAheadOfUnsafeOffset(
            String scenario,
            LegacyErrorCase errorCase
    ) throws Exception {
        MockHttpServletRequestBuilder request = get(errorCase.path())
                .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID);
        errorCase.parameters().forEach(parameter -> request.queryParam(
                parameter.name(), parameter.values()
        ));

        String response = mockMvc.perform(request)
                .andExpect(status().is(errorCase.status()))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER, TRACE_ID
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
                "1073741824",
                "2147483648",
                "FraudCaseAuditLogQueryValidator",
                "Spring Data",
                "JPA",
                "SQL",
                "credential",
                "Authorization",
                "cookie"
        );
        verifyNoInteractions(fraudCaseRepository, auditLogRepository);
    }

    private static Stream<Arguments> unsafePaginationLegacyErrors() {
        return Stream.of(
                legacy(
                        "unknown query",
                        PATH,
                        400,
                        "$",
                        "UNSUPPORTED_QUERY_PARAMETER",
                        "Query parameter is not supported",
                        query("unknown", "credential-secret")
                ),
                repeated("page", "1073741824", "1073741825"),
                repeated("size", "2", "3"),
                repeated("sort", "changedAt,asc", "changedAt,desc"),
                legacy(
                        "caseId format",
                        "/api/v1/cases/credential-secret/audit-logs",
                        400,
                        "caseId",
                        "INVALID_UUID_FORMAT",
                        "caseId must use the canonical UUID string format"
                ),
                legacy(
                        "caseId version",
                        "/api/v1/cases/6ba7b810-9dad-11d1-80b4-00c04fd430c8/audit-logs",
                        400,
                        "caseId",
                        "INVALID_UUID_VERSION",
                        "caseId must be a UUID version 4"
                ),
                legacy(
                        "caseId variant",
                        "/api/v1/cases/10000000-0000-4000-7000-000000000001/audit-logs",
                        400,
                        "caseId",
                        "INVALID_UUID_VARIANT",
                        "caseId must use the RFC 4122 variant"
                ),
                legacy(
                        "page format",
                        PATH,
                        400,
                        "page",
                        "INVALID_PAGE_FORMAT",
                        "page must be an integer",
                        query("page", "not-a-page")
                ),
                legacy(
                        "size format",
                        PATH,
                        400,
                        "size",
                        "INVALID_SIZE_FORMAT",
                        "size must be an integer",
                        query("size", "not-a-size")
                ),
                legacy(
                        "negative page",
                        PATH,
                        422,
                        "page",
                        "PAGE_OUT_OF_RANGE",
                        "page must be zero or greater",
                        query("page", "-1")
                ),
                legacy(
                        "zero size",
                        PATH,
                        422,
                        "size",
                        "SIZE_OUT_OF_RANGE",
                        "size must be between 1 and 100",
                        query("size", "0")
                ),
                legacy(
                        "size above maximum",
                        PATH,
                        422,
                        "size",
                        "SIZE_OUT_OF_RANGE",
                        "size must be between 1 and 100",
                        query("size", "101")
                ),
                legacy(
                        "sort format",
                        PATH,
                        400,
                        "sort",
                        "INVALID_SORT_FORMAT",
                        "sort must use field,direction format",
                        query("sort", "changedAt")
                ),
                legacy(
                        "sort field",
                        PATH,
                        400,
                        "sort",
                        "UNSUPPORTED_SORT_FIELD",
                        "sort field is not supported",
                        query("sort", "id,asc")
                ),
                legacy(
                        "sort direction",
                        PATH,
                        400,
                        "sort",
                        "UNSUPPORTED_SORT_DIRECTION",
                        "sort direction is not supported",
                        query("sort", "changedAt,up")
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
                PATH,
                400,
                field,
                "MULTIPLE_VALUES_NOT_ALLOWED",
                field + " must be provided exactly once",
                query(field, first, second)
        );
    }

    private static LegacyErrorCase legacy(
            String scenario,
            String path,
            int status,
            String field,
            String code,
            String reason,
            QueryParameter... invalidParameters
    ) {
        java.util.ArrayList<QueryParameter> parameters =
                new java.util.ArrayList<>(List.of(invalidParameters));
        if (parameters.stream().noneMatch(parameter ->
                "page".equals(parameter.name()))) {
            parameters.add(query("page", "1073741824"));
        }
        if (parameters.stream().noneMatch(parameter ->
                "size".equals(parameter.name()))) {
            parameters.add(query("size", "2"));
        }
        if (parameters.stream().noneMatch(parameter ->
                "sort".equals(parameter.name()))) {
            parameters.add(query("sort", "changedAt,asc"));
        }
        return new LegacyErrorCase(
                scenario, path, status, field, code, reason,
                List.copyOf(parameters)
        );
    }

    private static QueryParameter query(String name, String... values) {
        return new QueryParameter(name, values);
    }

    private record LegacyErrorCase(
            String scenario,
            String path,
            int status,
            String field,
            String code,
            String reason,
            List<QueryParameter> parameters
    ) {}

    private record QueryParameter(String name, String[] values) {}

    @Test
    void mappingFailureReturnsSafe500ForTheWholePage() throws Exception {
        AuditLog corrupted = org.mockito.Mockito.mock(AuditLog.class);
        when(corrupted.getAction()).thenReturn(AuditAction.CASE_CREATED);
        when(corrupted.getReasonCode()).thenReturn(
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY
        );
        when(corrupted.getActorType()).thenReturn(AuditActorType.SYSTEM);
        when(corrupted.getActorId()).thenReturn(AuditLog.SYSTEM_ACTOR_ID);
        when(corrupted.getTargetType()).thenReturn(AuditTargetType.FRAUD_CASE);
        when(corrupted.getTargetId()).thenReturn(CASE_ID);
        when(corrupted.getCaseId()).thenReturn(CASE_ID);
        when(corrupted.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(corrupted.getChangedAt()).thenReturn(
                Instant.parse("2026-09-01T00:00:00Z")
        );
        when(corrupted.getAfterValueSummary()).thenReturn(
                objectMapper.createObjectNode()
                        .put("caseStatus", "OPEN")
                        .put("credential", "secret")
        );
        when(corrupted.getMetadata()).thenReturn(
                objectMapper.createObjectNode()
        );
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(corrupted)));

        assertSafeError(PATH, 500, "INTERNAL_ERROR");
    }

    private void assertSafeError(
            String path,
            int expectedStatus,
            String expectedCode
    ) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER, TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(
                "credential-secret", "credential", "password", "secret",
                "raw_column", "SELECT", "schema", "stackTrace",
                "IllegalArgumentException", CASE_ID.toString()
        );
    }

    private AuditLog caseCreated() {
        return AuditLog.create(
                UUID.randomUUID(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                CASE_ID,
                TRANSACTION_ID,
                CASE_ID,
                "trace_stored_must_not_be_exposed",
                null,
                objectMapper.createObjectNode().put("caseStatus", "OPEN"),
                objectMapper.createObjectNode()
                        .put("detectionResultId", UUID.randomUUID().toString())
                        .put("detectionResultVersion", 1),
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }

    private Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void assertExcluded(String response) {
        assertThat(response).doesNotContain(
                "\"id\"", "auditId", "actorId", "targetType", "targetId",
                "transactionId", "detectionResultId", "stored_must_not",
                "fraudCaseId", "snapshot", "payload", "credential"
        );
    }
}
