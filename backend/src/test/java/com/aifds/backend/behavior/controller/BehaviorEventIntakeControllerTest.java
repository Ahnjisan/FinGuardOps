package com.aifds.backend.behavior.controller;

import com.aifds.backend.behavior.dto.BehaviorEventResponseMapper;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.exception.BehaviorEventConcurrentResultNotFoundException;
import com.aifds.backend.behavior.exception.BehaviorEventDependencyTimeoutException;
import com.aifds.backend.behavior.exception.BehaviorEventDependencyUnavailableException;
import com.aifds.backend.behavior.exception.BehaviorEventTransactionNotFoundException;
import com.aifds.backend.behavior.exception.DuplicateBehaviorEventException;
import com.aifds.backend.behavior.service.BehaviorEventIntakeResult;
import com.aifds.backend.behavior.service.BehaviorEventIntakeService;
import com.aifds.backend.behavior.service.BehaviorEventIntakeSnapshot;
import com.aifds.backend.behavior.validation.BehaviorEventValidationException;
import com.aifds.backend.behavior.validation.BehaviorEventValidationType;
import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BehaviorEventIntakeController.class)
@org.springframework.security.test.context.support.WithMockUser(
        authorities = "behavior-event:intake"
)
@Import({
        com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        BehaviorEventResponseMapper.class
})
class BehaviorEventIntakeControllerTest {

    private static final String PATH = "/api/v1/behavior-events";
    private static final UUID EVENT_ID = UUID.fromString(
            "e54cbf7e-d857-4ca0-bff3-8d4321b7722a"
    );
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-07-29T04:10:00Z");
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T04:10:01Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BehaviorEventIntakeService service;

    @Test
    void firstIntakeReturns201AndExactlySixPublicFields() throws Exception {
        String traceId = "trace_behavior_created_01";
        when(service.receive(any())).thenReturn(
                new BehaviorEventIntakeResult.Created(snapshot())
        );

        MvcResult result = perform(validJson(), traceId)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.eventType")
                        .value("BENEFICIARY_REGISTERED"))
                .andExpect(jsonPath("$.transactionId").value(nullValue()))
                .andExpect(jsonPath("$.occurredAt")
                        .value("2026-07-29T04:10:00Z"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-07-29T04:10:01Z"))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andReturn();

        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "eventId",
                "eventType",
                "transactionId",
                "occurredAt",
                "createdAt",
                "traceId"
        );
        assertThat(body.has("requestFingerprint")).isFalse();
        assertThat(body.has("externalCustomerRef")).isFalse();
        assertThat(body.has("accountRef")).isFalse();
        assertThat(body.has("deviceRef")).isFalse();
        assertThat(body.has("beneficiaryRef")).isFalse();
        assertThat(body.has("id")).isFalse();
    }

    @Test
    void replayReturns200WithCurrentRequestTraceId() throws Exception {
        String traceId = "trace_behavior_replay_02";
        when(service.receive(any())).thenReturn(
                new BehaviorEventIntakeResult.Replay(snapshot())
        );

        perform(validJson(), traceId)
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void rejectsUnknownDuplicateAndWrongTypeJsonBeforeService() throws Exception {
        perform(validJson().replace(
                        "\"beneficiaryRef\": \"acct_ref_demo_r82a\"",
                        "\"beneficiaryRef\": \"acct_ref_demo_r82a\","
                                + "\"unknown\": \"secret\""
                ), "trace_unknown_01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("UNKNOWN_JSON_FIELD"));

        perform(validJson().replace(
                        "\"eventType\": \"BENEFICIARY_REGISTERED\",",
                        "\"eventType\": \"BENEFICIARY_REGISTERED\","
                                + "\"eventType\": \"LOGIN\","
                ), "trace_duplicate_01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("DUPLICATE_JSON_FIELD"));

        perform(validJson().replace(
                        "\"deviceRef\": \"device_ref_demo_18b3\"",
                        "\"deviceRef\": 7"
                ), "trace_type_01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("INVALID_JSON_TOKEN"));

        verify(service, never()).receive(any());
    }

    @Test
    void malformedAndMissingCommonFieldsReturn400() throws Exception {
        perform("{", "trace_malformed_01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        perform(validJson().replace(
                        "\"eventId\": \"" + EVENT_ID + "\",",
                        ""
                ), "trace_missing_01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("eventId"));
    }

    @Test
    void domainValidationReturns422WithSameHeaderAndBodyTraceId()
            throws Exception {
        String traceId = "trace_behavior_domain_01";
        when(service.receive(any())).thenThrow(
                new BehaviorEventValidationException(
                        BehaviorEventValidationType.DOMAIN,
                        "accountRef",
                        "RELATED_TRANSACTION_ACCOUNT_MISMATCH",
                        "Related transaction account does not match"
                )
        );

        assertError(traceId, 422, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("accountRef"));
    }

    @Test
    void mapsNotFoundAndConflictWithoutLeakingReferences() throws Exception {
        doThrow(new BehaviorEventTransactionNotFoundException())
                .when(service).receive(any());
        MvcResult notFound = assertError(
                "trace_behavior_404",
                404,
                "RESOURCE_NOT_FOUND"
        ).andReturn();
        assertNoPrivateData(notFound);

        doThrow(new DuplicateBehaviorEventException())
                .when(service).receive(any());
        MvcResult conflict = assertError(
                "trace_behavior_409",
                409,
                "DUPLICATE_EVENT"
        ).andReturn();
        assertNoPrivateData(conflict);
    }

    @Test
    void mapsDatabaseTimeoutUnavailableAndOtherFailuresSeparately()
            throws Exception {
        doThrow(new BehaviorEventDependencyTimeoutException(
                        new RuntimeException("statement timeout secret")
                )).when(service).receive(any());
        assertError(
                "trace_behavior_timeout",
                503,
                "DEPENDENCY_TIMEOUT"
        );

        doThrow(new BehaviorEventDependencyUnavailableException(
                        new RuntimeException("connection secret")
                )).when(service).receive(any());
        assertError(
                "trace_behavior_unavailable",
                503,
                "DEPENDENCY_UNAVAILABLE"
        );

        doThrow(new InvalidDataAccessResourceUsageException(
                        "select secret_table"
                )).when(service).receive(any());
        MvcResult internal = assertError(
                "trace_behavior_internal",
                500,
                "INTERNAL_ERROR"
        ).andReturn();
        assertNoPrivateData(internal);
    }

    @Test
    void contradictoryConcurrentStorageResultReturns500() throws Exception {
        when(service.receive(any())).thenThrow(
                new BehaviorEventConcurrentResultNotFoundException()
        );

        assertError(
                "trace_behavior_contradiction",
                500,
                "INTERNAL_ERROR"
        );
    }

    private org.springframework.test.web.servlet.ResultActions assertError(
            String traceId,
            int statusCode,
            String code
    ) throws Exception {
        return perform(validJson(), traceId)
                .andExpect(status().is(statusCode))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.code").value(code));
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            String json,
            String traceId
    ) throws Exception {
        return mockMvc.perform(post(PATH)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String validJson() {
        return """
                {
                  "eventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
                  "eventType": "BENEFICIARY_REGISTERED",
                  "occurredAt": "2026-07-29T04:10:00Z",
                  "externalCustomerRef": "cust_ref_demo_a7f2",
                  "accountRef": "acct_ref_demo_s91c",
                  "deviceRef": "device_ref_demo_18b3",
                  "transactionId": null,
                  "beneficiaryRef": "acct_ref_demo_r82a"
                }
                """;
    }

    private static BehaviorEventIntakeSnapshot snapshot() {
        return new BehaviorEventIntakeSnapshot(
                EVENT_ID,
                BehaviorEventType.BENEFICIARY_REGISTERED,
                null,
                OCCURRED_AT,
                CREATED_AT,
                "a".repeat(64)
        );
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertNoPrivateData(MvcResult result)
            throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(
                        "cust_ref_demo_a7f2",
                        "acct_ref_demo_s91c",
                        "device_ref_demo_18b3",
                        "acct_ref_demo_r82a",
                        "requestFingerprint",
                        "secret_table",
                        "statement timeout",
                        "connection secret"
                );
    }
}
