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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InvestigationNoteController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, InvestigationNoteValidator.class})
class InvestigationNoteControllerTest {

    private static final String CASE_ID = "10000000-0000-4000-8000-000000000001";
    private static final String PATH = "/api/v1/cases/" + CASE_ID + "/notes";
    private static final String TRACE = "trace_note_001";

    @Autowired MockMvc mockMvc;
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
}
