package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.dto.FraudCasePageMetadataResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListItemResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListResponse;
import com.aifds.backend.fraudcase.entity.InvestigationNoteAuthorType;
import com.aifds.backend.fraudcase.service.InvestigationNoteService;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InvestigationNoteController.class)
@org.springframework.security.test.context.support.WithMockUser(
        authorities = {"case-note:read", "case-note:write"}
)
@Import({
        com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        InvestigationNoteValidator.class
})
class InvestigationNoteControllerTest {

    private static final String CASE_ID = "10000000-0000-4000-8000-000000000001";
    private static final String PATH = "/api/v1/cases/" + CASE_ID + "/notes";
    private static final String TRACE = "trace_note_001";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean InvestigationNoteService service;

    @Test
    void createsExactContractWithMatchingTraceAndPreservedPlainText() throws Exception {
        UUID noteId = UUID.randomUUID();
        when(service.create(any(), eq(TRACE))).thenReturn(new InvestigationNoteCreateResponse(
                noteId, UUID.fromString(CASE_ID), InvestigationNoteAuthorType.SYSTEM,
                "finguardops-backend", "  <script>x</script>\r\n  ",
                Instant.parse("2026-09-02T00:00:00.123456Z"), 7, TRACE
        ));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE)
                        .content("{\"content\":\"  <script>x</script>\\r\\n  \",\"expectedVersion\":6}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(jsonPath("$.noteId").value(noteId.toString()))
                .andExpect(jsonPath("$.authorType").value("SYSTEM"))
                .andExpect(jsonPath("$.authorRef").value("finguardops-backend"))
                .andExpect(jsonPath("$.content").value("  <script>x</script>\r\n  "))
                .andExpect(jsonPath("$.concurrencyVersion").value(7))
                .andExpect(jsonPath("$.traceId").value(TRACE));
    }

    @Test
    void listsCompleteContractWithMatchingTraceAndNoInternalId() throws Exception {
        UUID noteId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-02T00:00:00.123456Z");
        when(service.list(any(), eq(TRACE))).thenReturn(
                new InvestigationNoteListResponse(
                        List.of(new InvestigationNoteListItemResponse(
                                noteId,
                                UUID.fromString(CASE_ID),
                                InvestigationNoteAuthorType.SYSTEM,
                                "finguardops-backend",
                                "untrusted <b>plain text</b>",
                                createdAt
                        )),
                        new FraudCasePageMetadataResponse(
                                0, 2, 1, 1, true, true
                        ),
                        TRACE
                )
        );

        mockMvc.perform(get(PATH + "?page=0&size=2&sort=createdAt,desc")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].noteId").value(noteId.toString()))
                .andExpect(jsonPath("$.items[0].caseId").value(CASE_ID))
                .andExpect(jsonPath("$.items[0].authorType").value("SYSTEM"))
                .andExpect(jsonPath("$.items[0].authorRef")
                        .value("finguardops-backend"))
                .andExpect(jsonPath("$.items[0].content")
                        .value("untrusted <b>plain text</b>"))
                .andExpect(jsonPath("$.items[0].createdAt")
                        .value(createdAt.toString()))
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true))
                .andExpect(jsonPath("$.traceId").value(TRACE));
    }

    @Test
    void returnsOkWithEmptyItemsAndMatchingTrace() throws Exception {
        when(service.list(any(), eq(TRACE))).thenReturn(
                new InvestigationNoteListResponse(
                        List.of(),
                        new FraudCasePageMetadataResponse(
                                0, 20, 0, 0, true, true
                        ),
                        TRACE
                )
        );

        mockMvc.perform(get(PATH)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true))
                .andExpect(jsonPath("$.traceId").value(TRACE));
    }

    @Test
    void rejectsUnknownDuplicateTrailingWrongRootAndExpectedVersionTypes() throws Exception {
        for (String body : new String[]{
                "null", "[]", "1", "{}",
                "{\"expectedVersion\":6}",
                "{\"content\":null,\"expectedVersion\":6}",
                "{\"content\":1,\"expectedVersion\":6}",
                "{\"content\":\"x\"}",
                "{\"content\":\"x\",\"expectedVersion\":null}",
                "{\"content\":\"x\",\"authorRef\":\"attacker\",\"expectedVersion\":6}",
                "{\"content\":\"x\",\"content\":\"y\",\"expectedVersion\":6}",
                "{\"content\":\"x\",\"expectedVersion\":\"6\"}",
                "{\"content\":\"x\",\"expectedVersion\":6.0}",
                "{\"content\":\"x\",\"expectedVersion\":true}",
                "{\"content\":\"x\",\"expectedVersion\":9223372036854775808}",
                "{\"content\":\"x\",\"expectedVersion\":6,}",
                "{\"content\":\"x\",\"expectedVersion\":6} {}"
        }) {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .header(TraceIdFilter.TRACE_ID_HEADER, TRACE).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.traceId").value(TRACE));
        }
    }

    @Test
    void distinguishesQueryFormatAndDomainErrors() throws Exception {
        mockMvc.perform(get(PATH + "?page=0&page=1")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH + "?size=101")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get(PATH + "?sort=id,asc")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsafePaginationAsNonReflective422BeforeServiceCall()
            throws Exception {
        when(service.list(any(), any())).thenThrow(
                new IllegalArgumentException(
                        "Spring Data JPA SQL credential Authorization cookie"
                )
        );

        MvcResult result = mockMvc.perform(get(PATH)
                        .queryParam("page", "1073741824")
                        .queryParam("size", "2")
                        .queryParam("sort", "createdAt,asc")
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE)
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
                )).isEqualTo(TRACE),
                () -> assertThat(body.path("code").asText())
                        .isEqualTo("VALIDATION_ERROR"),
                () -> assertThat(body.path("message").asText())
                        .isEqualTo("요청 필드를 확인해 주세요."),
                () -> assertThat(body.path("fieldErrors").size()).isEqualTo(1),
                () -> assertThat(body.at("/fieldErrors/0/field").asText())
                        .isEqualTo("page"),
                () -> assertThat(body.at("/fieldErrors/0/code").asText())
                        .isEqualTo("INVALID_PAGE"),
                () -> assertThat(body.at("/fieldErrors/0/reason").asText())
                        .isEqualTo(
                                "page is too large for the requested size"
                        ),
                () -> assertThat(body.path("traceId").asText())
                        .isEqualTo(TRACE),
                () -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(
                                "1073741824",
                                "\"2\"",
                                "2147483648",
                                "createdAt,asc",
                                CASE_ID,
                                "InvestigationNoteValidationException",
                                "IllegalArgumentException",
                                "PageRequest",
                                "Spring Data",
                                "JPA",
                                "SQL",
                                "credential",
                                "cookie"
                        ),
                () -> verifyNoInteractions(service)
        );
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("unsafePaginationLegacyErrors")
    void preservesLegacyListValidationContractAheadOfUnsafeOffset(
            String scenario,
            LegacyErrorCase errorCase
    ) throws Exception {
        MockHttpServletRequestBuilder request = get(errorCase.path())
                .header(TraceIdFilter.TRACE_ID_HEADER, TRACE);
        errorCase.parameters().forEach(parameter -> request.queryParam(
                parameter.name(), parameter.values()
        ));

        String response = mockMvc.perform(request)
                .andExpect(status().is(errorCase.status()))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER, TRACE
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
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(
                "page is too large for the requested size",
                "1073741824",
                "2147483648",
                "InvestigationNoteValidator",
                "Spring Data",
                "JPA",
                "SQL",
                "credential",
                "Authorization",
                "cookie"
        );
        verifyNoInteractions(service);
    }

    private static Stream<Arguments> unsafePaginationLegacyErrors() {
        return Stream.of(
                legacyPath(
                        "invalid caseId",
                        "/api/v1/cases/credential-secret/notes",
                        400,
                        "caseId",
                        "INVALID_UUID_FORMAT",
                        "caseId must be a canonical lowercase UUID v4"
                ),
                legacy(
                        "unknown query",
                        400,
                        "unknown",
                        "UNKNOWN_QUERY_PARAMETER",
                        "Unknown note list query parameter",
                        query("unknown", "credential-secret")
                ),
                repeated("page", "1073741824", "1073741825"),
                repeated("size", "2", "3"),
                repeated("sort", "createdAt,asc", "createdAt,desc"),
                legacy(
                        "page format",
                        400,
                        "page",
                        "INVALID_INTEGER",
                        "page must be an integer",
                        query("page", "not-a-page")
                ),
                legacy(
                        "size format",
                        400,
                        "size",
                        "INVALID_INTEGER",
                        "size must be an integer",
                        query("size", "not-a-size")
                ),
                legacy(
                        "negative page",
                        422,
                        "page",
                        "INVALID_PAGE",
                        "page must be zero or greater",
                        query("page", "-1")
                ),
                legacy(
                        "zero size",
                        422,
                        "size",
                        "INVALID_SIZE",
                        "size must be between 1 and 100",
                        query("size", "0")
                ),
                legacy(
                        "size above maximum",
                        422,
                        "size",
                        "INVALID_SIZE",
                        "size must be between 1 and 100",
                        query("size", "101")
                ),
                legacy(
                        "invalid sort",
                        400,
                        "sort",
                        "INVALID_SORT",
                        "sort must be createdAt,asc or createdAt,desc",
                        query("sort", "id,asc")
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
                "DUPLICATE_QUERY_PARAMETER",
                "Note list query parameter must occur once",
                query(field, first, second)
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
        return legacyPath(
                scenario, PATH, status, field, code, reason,
                invalidParameters
        );
    }

    private static LegacyErrorCase legacyPath(
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
            parameters.add(query("sort", "createdAt,asc"));
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
}
