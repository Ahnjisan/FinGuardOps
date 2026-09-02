package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseWorkflowException;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationException;
import com.aifds.backend.fraudcase.validation.FraudCaseValidationType;
import com.aifds.backend.fraudcase.validation.FraudCaseWorkflowValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCaseWorkflowController.class)
@org.springframework.security.test.context.support.WithMockUser
@Import({
        com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        FraudCaseWorkflowValidator.class
})
class FraudCaseWorkflowControllerTest {

    private static final String CASE_ID =
            "1a000000-0000-4000-9000-000000000001";
    private static final String ASSIGNEE =
            "2a000000-0000-4000-9000-000000000002";
    private static final String TRACE_ID = "trace_case_workflow_01";
    private static final String STATUS_PATH =
            "/api/v1/cases/" + CASE_ID + "/status";
    private static final String ASSIGNEE_PATH =
            "/api/v1/cases/" + CASE_ID + "/assignee";
    private static final String RESOLUTION_PATH =
            "/api/v1/cases/" + CASE_ID + "/resolution";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudCaseWorkflowService service;

    @BeforeEach
    void resetService() {
        reset(service);
    }

    @Test
    void returnsExactStatusMutationContractAndMatchingTraceId()
            throws Exception {
        when(service.changeStatus(any(), eq(TRACE_ID)))
                .thenReturn(response());

        String body = mockMvc.perform(patch(STATUS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content("""
                                {
                                  "targetStatus": "IN_REVIEW",
                                  "assigneeRef": "%s",
                                  "reasonCode": "CASE_REVIEW_STARTED",
                                  "expectedVersion": 0
                                }
                                """.formatted(ASSIGNEE)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.caseId").value(CASE_ID))
                .andExpect(jsonPath("$.caseStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.assigneeRef").value(ASSIGNEE))
                .andExpect(jsonPath("$.finalDisposition").value(
                        org.hamcrest.Matchers.nullValue()
                ))
                .andExpect(jsonPath("$.closedAt").value(
                        org.hamcrest.Matchers.nullValue()
                ))
                .andExpect(jsonPath("$.concurrencyVersion").value(1))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(
                "fraudCaseId", "transactionId", "credential", "stackTrace"
        );
    }

    @Test
    void acceptsExplicitNullReleaseButRejectsMissingAssigneeCommand()
            throws Exception {
        when(service.changeAssignee(any(), eq(TRACE_ID)))
                .thenReturn(response());

        mockMvc.perform(patch(ASSIGNEE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content("""
                                {
                                  "assigneeRef": null,
                                  "reasonCode": "CASE_ASSIGNEE_RELEASED",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch(ASSIGNEE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content("""
                                {
                                  "reasonCode": "CASE_ASSIGNEE_RELEASED",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("assigneeRef"));
    }

    @Test
    void rejectsUnknownDuplicateNullRequiredAndWrongJsonTypes()
            throws Exception {
        for (String body : new String[]{
                "{\"targetStatus\":\"IN_REVIEW\",\"unknown\":true}",
                "{\"targetStatus\":\"IN_REVIEW\","
                        + "\"targetStatus\":\"OPEN\"}",
                "{\"targetStatus\":null}",
                "{\"targetStatus\":1}",
                "{\"targetStatus\":\"IN_REVIEW\"} {}",
                "[]",
                "null"
        }) {
            mockMvc.perform(patch(STATUS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID));
        }
    }

    @Test
    void rejectsUnsafeAssigneeFormsWithSafe422WithoutEcho()
            throws Exception {
        for (String invalid : new String[]{
                ASSIGNEE.toUpperCase(),
                ASSIGNEE + " ",
                "analyst@example.com",
                "credential-secret"
        }) {
            String body = mockMvc.perform(patch(ASSIGNEE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                            .content("""
                                    {
                                      "assigneeRef": "%s",
                                      "reasonCode": "CASE_ASSIGNEE_CHANGED",
                                      "expectedVersion": 0
                                    }
                                    """.formatted(invalid)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_ASSIGNEE_REF"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(invalid, "credential-secret");
        }
    }

    @Test
    void rejectsUnsafeCaseIdAndStrictAssigneeJsonWithSafe400()
            throws Exception {
        String invalidCaseId = "1a000000-0000-1000-9000-credential001";
        String invalidPath = "/api/v1/cases/" + invalidCaseId + "/assignee";
        String invalidCaseBody = mockMvc.perform(patch(invalidPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content(validAssigneeBody()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(invalidCaseBody).doesNotContain(
                invalidCaseId,
                "credential001",
                "JsonMappingException",
                "stackTrace"
        );

        for (String invalidJson : new String[]{
                "{\"assigneeRef\":null,\"unknown\":true}",
                "{\"assigneeRef\":null,\"assigneeRef\":\""
                        + ASSIGNEE + "\"}",
                validAssigneeBody() + " {}",
                "{\"assigneeRef\":123,"
                        + "\"reasonCode\":\"CASE_ASSIGNEE_CHANGED\","
                        + "\"expectedVersion\":0}",
                "{\"assigneeRef\":null,"
                        + "\"reasonCode\":\"CASE_ASSIGNEE_RELEASED\","
                        + "\"expectedVersion\":false}"
        }) {
            String response = mockMvc.perform(patch(ASSIGNEE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(
                            TraceIdFilter.TRACE_ID_HEADER,
                            TRACE_ID
                    ))
                    .andExpect(jsonPath("$.code")
                            .value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(
                    invalidJson,
                    ASSIGNEE,
                    "JsonParser",
                    "JsonMappingException",
                    "InputCoercionException",
                    "stackTrace"
            );
        }
    }

    @Test
    void returnsSafe422ForReasonCodeThatDoesNotMatchAssigneeCommand()
            throws Exception {
        when(service.changeAssignee(any(), eq(TRACE_ID))).thenThrow(
                new FraudCaseValidationException(
                        FraudCaseValidationType.DOMAIN,
                        "reasonCode",
                        "REASON_CODE_MISMATCH",
                        "reasonCode does not match the requested case change"
                )
        );

        String response = mockMvc.perform(patch(ASSIGNEE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content("""
                                {
                                  "assigneeRef": "%s",
                                  "reasonCode": "CASE_REVIEW_STARTED",
                                  "expectedVersion": 0
                                }
                                """.formatted(ASSIGNEE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("reasonCode"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("REASON_CODE_MISMATCH"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(
                ASSIGNEE,
                "FraudCaseValidationException",
                "stackTrace"
        );
    }

    @Test
    void mapsEveryWorkflowErrorToApprovedSafeContract() throws Exception {
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT,
                409,
                "CASE_STATUS_CONFLICT",
                STATUS_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.CASE_ASSIGNEE_CONFLICT,
                409,
                "CASE_ASSIGNEE_CONFLICT",
                ASSIGNEE_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.CASE_ALREADY_CLOSED,
                409,
                "CASE_ALREADY_CLOSED",
                STATUS_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                409,
                "CONCURRENT_MODIFICATION",
                STATUS_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                409,
                "CONCURRENT_MODIFICATION",
                ASSIGNEE_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.ASSIGNEE_REQUIRED,
                422,
                "ASSIGNEE_REQUIRED",
                STATUS_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.DEPENDENCY_TIMEOUT,
                503,
                "DEPENDENCY_TIMEOUT",
                STATUS_PATH
        );
        assertWorkflowError(
                FraudCaseWorkflowException.Reason.DEPENDENCY_UNAVAILABLE,
                503,
                "DEPENDENCY_UNAVAILABLE",
                STATUS_PATH
        );
    }

    @Test
    void mapsNotFoundAndUnexpectedFailureWithoutInternalDisclosure()
            throws Exception {
        when(service.changeStatus(any(), any()))
                .thenThrow(new FraudCaseNotFoundException());
        performValidStatus()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        reset(service);
        when(service.changeStatus(any(), any())).thenThrow(
                new IllegalStateException("SELECT password FROM credential")
        );
        String body = performValidStatus()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "SELECT", "password", "credential", "IllegalStateException"
        );
    }

    @Test
    void returnsExactResolutionContractAndMatchingTraceId() throws Exception {
        when(service.resolve(any(), eq(TRACE_ID)))
                .thenReturn(resolutionResponse());

        String body = mockMvc.perform(post(RESOLUTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content(validResolutionBody()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.caseId").value(CASE_ID))
                .andExpect(jsonPath("$.caseStatus").value("CLOSED"))
                .andExpect(jsonPath("$.finalDisposition")
                        .value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.assigneeRef").value(ASSIGNEE))
                .andExpect(jsonPath("$.reviewStartedAt")
                        .value("2026-09-01T00:05:00.123456Z"))
                .andExpect(jsonPath("$.closedAt")
                        .value("2026-09-01T00:10:00.123456Z"))
                .andExpect(jsonPath("$.lastChangedAt")
                        .value("2026-09-01T00:10:00.123456Z"))
                .andExpect(jsonPath("$.concurrencyVersion").value(7))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(
                "id\"", "transactionId", "credential", "stackTrace"
        );
    }

    @Test
    void resolutionReturnsDedicated422ForMissingOrNullDisposition()
            throws Exception {
        for (String body : new String[]{
                "{\"reasonCode\":\"CASE_RESOLUTION_COMPLETED\","
                        + "\"expectedVersion\":6}",
                "{\"finalDisposition\":null,"
                        + "\"reasonCode\":\"CASE_RESOLUTION_COMPLETED\","
                        + "\"expectedVersion\":6}"
        }) {
            mockMvc.perform(post(RESOLUTION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code")
                            .value("FINAL_DISPOSITION_REQUIRED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value("finalDisposition"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID));
        }
    }

    @Test
    void resolutionRejectsStructuralTypePathAndCoercionMatrixSafely()
            throws Exception {
        String unsafe = "credential-secret";
        for (String body : new String[]{
                "null",
                "[]",
                "1",
                "{\"unknown\":\"" + unsafe + "\"}",
                "{\"finalDisposition\":\"NORMAL\","
                        + "\"finalDisposition\":\"FALSE_POSITIVE\"}",
                "{\"finalDisposition\":1}",
                "{\"reasonCode\":true}",
                "{\"expectedVersion\":\"6\"}",
                "{\"expectedVersion\":6.5}",
                "{\"expectedVersion\":true}",
                "{\"expectedVersion\":9223372036854775808}",
                validResolutionBody() + " {}"
        }) {
            String response = mockMvc.perform(post(RESOLUTION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(
                    unsafe,
                    "JsonMappingException",
                    "InputCoercionException",
                    "stackTrace"
            );
        }

        mockMvc.perform(post(
                        "/api/v1/cases/1a000000-0000-1000-9000-000000000001"
                                + "/resolution"
                ).contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content(validResolutionBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void resolutionMaps404Conflict422DependencyAndInternalErrorsSafely()
            throws Exception {
        assertResolutionFailure(
                new FraudCaseNotFoundException(),
                404,
                "RESOURCE_NOT_FOUND"
        );
        for (FraudCaseWorkflowException.Reason reason
                : new FraudCaseWorkflowException.Reason[]{
                FraudCaseWorkflowException.Reason.CONCURRENT_MODIFICATION,
                FraudCaseWorkflowException.Reason.CASE_ALREADY_CLOSED,
                FraudCaseWorkflowException.Reason.CASE_STATUS_CONFLICT
        }) {
            assertResolutionFailure(
                    new FraudCaseWorkflowException(reason),
                    409,
                    reason.name()
            );
        }
        assertResolutionFailure(
                new FraudCaseValidationException(
                        FraudCaseValidationType.DOMAIN,
                        "reasonCode",
                        "REASON_CODE_MISMATCH",
                        "reasonCode does not match case resolution"
                ),
                422,
                "VALIDATION_ERROR"
        );
        assertResolutionFailure(
                new FraudCaseWorkflowException(
                        FraudCaseWorkflowException.Reason.DEPENDENCY_TIMEOUT
                ),
                503,
                "DEPENDENCY_TIMEOUT"
        );
        assertResolutionFailure(
                new FraudCaseWorkflowException(
                        FraudCaseWorkflowException.Reason
                                .DEPENDENCY_UNAVAILABLE
                ),
                503,
                "DEPENDENCY_UNAVAILABLE"
        );
        assertResolutionFailure(
                new IllegalStateException(
                        "SELECT password FROM credential"
                ),
                500,
                "INTERNAL_ERROR"
        );
    }

    private void assertWorkflowError(
            FraudCaseWorkflowException.Reason reason,
            int status,
            String code,
            String path
    ) throws Exception {
        reset(service);
        if (ASSIGNEE_PATH.equals(path)) {
            when(service.changeAssignee(any(), any()))
                    .thenThrow(new FraudCaseWorkflowException(reason));
        } else {
            when(service.changeStatus(any(), any()))
                    .thenThrow(new FraudCaseWorkflowException(reason));
        }
        mockMvc.perform(patch(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content(ASSIGNEE_PATH.equals(path)
                                ? validAssigneeBody()
                                : validStatusBody()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    private org.springframework.test.web.servlet.ResultActions
    performValidStatus() throws Exception {
        return mockMvc.perform(patch(STATUS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                .content(validStatusBody()));
    }

    private String validStatusBody() {
        return """
                {
                  "targetStatus": "IN_REVIEW",
                  "assigneeRef": "%s",
                  "reasonCode": "CASE_REVIEW_STARTED",
                  "expectedVersion": 0
                }
                """.formatted(ASSIGNEE);
    }

    private String validAssigneeBody() {
        return """
                {
                  "assigneeRef": "%s",
                  "reasonCode": "CASE_ASSIGNEE_CHANGED",
                  "expectedVersion": 0
                }
                """.formatted(ASSIGNEE);
    }

    private String validResolutionBody() {
        return """
                {
                  "finalDisposition": "CONFIRMED_FRAUD",
                  "reasonCode": "CASE_RESOLUTION_COMPLETED",
                  "expectedVersion": 6
                }
                """;
    }

    private void assertResolutionFailure(
            RuntimeException failure,
            int expectedStatus,
            String expectedCode
    ) throws Exception {
        reset(service);
        when(service.resolve(any(), any())).thenThrow(failure);
        String body = mockMvc.perform(post(RESOLUTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
                        .content(validResolutionBody()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "SELECT", "password", "credential", "stackTrace"
        );
    }

    private FraudCaseMutationResponse response() {
        Instant now = Instant.parse("2026-09-01T00:10:00.123456Z");
        return new FraudCaseMutationResponse(
                UUID.fromString(CASE_ID),
                FraudCaseStatus.IN_REVIEW,
                null,
                ASSIGNEE,
                now,
                null,
                now,
                1L,
                TRACE_ID
        );
    }

    private FraudCaseMutationResponse resolutionResponse() {
        Instant reviewStartedAt =
                Instant.parse("2026-09-01T00:05:00.123456Z");
        Instant resolutionTime =
                Instant.parse("2026-09-01T00:10:00.123456Z");
        return new FraudCaseMutationResponse(
                UUID.fromString(CASE_ID),
                FraudCaseStatus.CLOSED,
                FraudCaseFinalDisposition.CONFIRMED_FRAUD,
                ASSIGNEE,
                reviewStartedAt,
                resolutionTime,
                resolutionTime,
                7L,
                TRACE_ID
        );
    }
}
