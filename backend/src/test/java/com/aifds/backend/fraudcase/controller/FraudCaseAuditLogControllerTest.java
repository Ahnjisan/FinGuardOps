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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
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
