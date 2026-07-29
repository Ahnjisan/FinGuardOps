package com.aifds.backend.transaction.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.InvalidTransactionIntakeSnapshotException;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionIntakeSnapshot;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionIntakeController.class)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class TransactionIntakeControllerTest {

    private static final String PATH = "/api/v1/transactions";
    private static final String KEY = "intake-controller-key";
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-23T01:15:31.123456Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionIntakeService transactionIntakeService;

    @Test
    void newIntakeReturns201AndExactlyEightContractFields() throws Exception {
        String traceId = "trace_new_intake_01";
        when(transactionIntakeService.receive(anyString(), any()))
                .thenReturn(new TransactionIntakeResult.Received(snapshot()));

        MvcResult result = performValid(KEY, traceId)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.transactionId")
                        .value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.riskLevel").value(nullValue()))
                .andExpect(jsonPath("$.riskResponseOutcome").value(nullValue()))
                .andExpect(jsonPath("$.adoptedDetectionResultId")
                        .value(nullValue()))
                .andExpect(jsonPath("$.caseId").value(nullValue()))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-07-23T01:15:31.123456Z"))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andReturn();

        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "transactionId",
                "processingStatus",
                "riskLevel",
                "riskResponseOutcome",
                "adoptedDetectionResultId",
                "caseId",
                "createdAt",
                "traceId"
        );
    }

    @Test
    void completedReplayReturns200WithStoredBusinessDataAndCurrentTraceId()
            throws Exception {
        String replayTraceId = "trace_replay_current_01";
        when(transactionIntakeService.receive(anyString(), any()))
                .thenReturn(new TransactionIntakeResult.CompletedReplay(
                        snapshot()
                ));

        performValid(KEY, replayTraceId)
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        replayTraceId
                ))
                .andExpect(jsonPath("$.transactionId")
                        .value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-07-23T01:15:31.123456Z"))
                .andExpect(jsonPath("$.traceId").value(replayTraceId))
                .andExpect(jsonPath("$.riskLevel").value(nullValue()))
                .andExpect(jsonPath("$.riskResponseOutcome").value(nullValue()))
                .andExpect(jsonPath("$.adoptedDetectionResultId")
                        .value(nullValue()))
                .andExpect(jsonPath("$.caseId").value(nullValue()));
    }

    @Test
    void missingIdempotencyKeyReturns400WithoutCallingService()
            throws Exception {
        assertErrorTrace(
                mockMvc.perform(post(PATH)
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                "trace_missing_key_01"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson())),
                400,
                "VALIDATION_ERROR",
                "trace_missing_key_01"
        ).andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("Idempotency-Key"));

        verify(transactionIntakeService, never())
                .receive(anyString(), any());
    }

    @Test
    void invalidIdempotencyKeyReturns400ValidationError() throws Exception {
        when(transactionIntakeService.receive(eq("short"), any()))
                .thenThrow(validation(
                        TransactionValidationType.FORMAT,
                        "Idempotency-Key",
                        "INVALID_IDEMPOTENCY_KEY"
                ));

        assertErrorTrace(
                perform("short", "trace_invalid_key_01", validJson()),
                400,
                "VALIDATION_ERROR",
                "trace_invalid_key_01"
        ).andExpect(jsonPath("$.fieldErrors[0].field")
                .value("Idempotency-Key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "transactionId",
            "transactionType",
            "amount",
            "currencyCode",
            "occurredAt",
            "externalCustomerRef",
            "senderAccountRef",
            "channel"
    })
    void everyRequiredFieldRejectsMissingAndNullValues(String field)
            throws Exception {
        ObjectNode missing = (ObjectNode) objectMapper.readTree(validJson());
        missing.remove(field);
        assertErrorTrace(
                perform(
                        KEY,
                        "trace_missing_field_01",
                        objectMapper.writeValueAsString(missing)
                ),
                400,
                "VALIDATION_ERROR",
                "trace_missing_field_01"
        ).andExpect(jsonPath("$.fieldErrors[0].field").value(field));

        ObjectNode explicitNull =
                (ObjectNode) objectMapper.readTree(validJson());
        explicitNull.putNull(field);
        assertErrorTrace(
                perform(
                        KEY,
                        "trace_null_field_01",
                        objectMapper.writeValueAsString(explicitNull)
                ),
                400,
                "VALIDATION_ERROR",
                "trace_null_field_01"
        ).andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }

    @Test
    void malformedUnknownDuplicateAndWrongTokenJsonReturn400()
            throws Exception {
        assertErrorTrace(
                perform(KEY, "trace_malformed_json_01", "{"),
                400,
                "VALIDATION_ERROR",
                "trace_malformed_json_01"
        );
        assertErrorTrace(
                perform(
                        KEY,
                        "trace_unknown_json_01",
                        validJson().replace(
                                "\"deviceRef\":\"device_ref_controller\"",
                                "\"deviceRef\":\"device_ref_controller\","
                                        + "\"accountNumber\":\"secret\""
                        )
                ),
                400,
                "VALIDATION_ERROR",
                "trace_unknown_json_01"
        ).andExpect(jsonPath("$.fieldErrors[0].code")
                .value("UNKNOWN_JSON_FIELD"));
        assertErrorTrace(
                perform(
                        KEY,
                        "trace_duplicate_json_01",
                        validJson().replace(
                                "\"amount\":\"1250000\"",
                                "\"amount\":\"1250000\",\"amount\":\"1\""
                        )
                ),
                400,
                "VALIDATION_ERROR",
                "trace_duplicate_json_01"
        ).andExpect(jsonPath("$.fieldErrors[0].code")
                .value("DUPLICATE_JSON_FIELD"));
        assertErrorTrace(
                perform(
                        KEY,
                        "trace_wrong_token_01",
                        validJson().replace(
                                "\"amount\":\"1250000\"",
                                "\"amount\":1250000"
                        )
                ),
                400,
                "VALIDATION_ERROR",
                "trace_wrong_token_01"
        ).andExpect(jsonPath("$.fieldErrors[0].code")
                .value("INVALID_JSON_TOKEN"));
    }

    @Test
    void invalidEnumReturns400ValidationError() throws Exception {
        when(transactionIntakeService.receive(
                eq(KEY),
                argThat(request -> "WIRE".equals(request.transactionType()))
        )).thenThrow(validation(
                TransactionValidationType.FORMAT,
                "transactionType",
                "UNSUPPORTED_TRANSACTION_TYPE"
        ));

        assertErrorTrace(
                perform(
                        KEY,
                        "trace_invalid_enum_01",
                        validJson().replace(
                                "ACCOUNT_TRANSFER",
                                "WIRE"
                        )
                ),
                400,
                "VALIDATION_ERROR",
                "trace_invalid_enum_01"
        ).andExpect(jsonPath("$.fieldErrors[0].field")
                .value("transactionType"));
    }

    @Test
    void fixedConflictResultsMapToTheirApproved409Errors() throws Exception {
        assertResultError(
                new TransactionIntakeResult.KeyConflict(),
                "trace_key_conflict_01",
                409,
                "IDEMPOTENCY_KEY_CONFLICT"
        );
        assertResultError(
                new TransactionIntakeResult.InProgress(),
                "trace_in_progress_01",
                409,
                "IDEMPOTENCY_REQUEST_IN_PROGRESS"
        );
        assertResultError(
                new TransactionIntakeResult.DuplicateTransaction(
                        TRANSACTION_ID
                ),
                "trace_duplicate_tx_01",
                409,
                "DUPLICATE_TRANSACTION"
        );
    }

    @Test
    void domainValidationReturns422ValidationError() throws Exception {
        when(transactionIntakeService.receive(anyString(), any()))
                .thenThrow(validation(
                        TransactionValidationType.DOMAIN,
                        "recipientAccountRef",
                        "RECIPIENT_ACCOUNT_REQUIRED"
                ));

        assertErrorTrace(
                performValid(KEY, "trace_domain_error_01"),
                422,
                "VALIDATION_ERROR",
                "trace_domain_error_01"
        );
    }

    @Test
    void unexpectedErrorsReturnSafe500() throws Exception {
        when(transactionIntakeService.receive(anyString(), any()))
                .thenThrow(new IllegalStateException(
                        "secret database account and request body"
                ));

        MvcResult result = assertErrorTrace(
                performValid(KEY, "trace_unexpected_500_01"),
                500,
                "INTERNAL_ERROR",
                "trace_unexpected_500_01"
        ).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret")
                .doesNotContain("database account");
    }

    @Test
    void previousFailureUsesOnlyThePublicWhitelist() throws Exception {
        assertResultError(
                new TransactionIntakeResult.PreviousFailure(
                        "DUPLICATE_TRANSACTION"
                ),
                "trace_failed_duplicate_01",
                409,
                "DUPLICATE_TRANSACTION"
        );
        assertResultError(
                new TransactionIntakeResult.PreviousFailure(
                        "DEPENDENCY_TIMEOUT"
                ),
                "trace_failed_timeout_01",
                503,
                "DEPENDENCY_TIMEOUT"
        );
    }

    @Test
    void unknownEmptyAndNullPreviousFailuresCollapseToSafe500()
            throws Exception {
        assertPreviousFailureIsInternal(
                "TRANSACTION_INTAKE_FAILED",
                "trace_failed_internal_01"
        );
        assertPreviousFailureIsInternal(
                "SECRET_INTERNAL_FAILURE",
                "trace_failed_unknown_01"
        );
        assertPreviousFailureIsInternal("", "trace_failed_empty_01");
        assertPreviousFailureIsInternal(null, "trace_failed_null_01");
    }

    @Test
    void invalidStoredSnapshotReturnsSafe500WithoutExposingSnapshot()
            throws Exception {
        String rawSnapshot =
                "{\"accountRef\":\"secret_account\",\"traceId\":\"old_trace\"}";
        when(transactionIntakeService.receive(anyString(), any()))
                .thenThrow(new InvalidTransactionIntakeSnapshotException(
                        new IllegalArgumentException(rawSnapshot)
                ));

        MvcResult result = assertErrorTrace(
                performValid(KEY, "trace_bad_snapshot_01"),
                500,
                "INTERNAL_ERROR",
                "trace_bad_snapshot_01"
        ).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(rawSnapshot)
                .doesNotContain("secret_account")
                .doesNotContain("old_trace");
    }

    private void assertPreviousFailureIsInternal(
            String failureCode,
            String traceId
    ) throws Exception {
        when(transactionIntakeService.receive(anyString(), any()))
                .thenReturn(new TransactionIntakeResult.PreviousFailure(
                        failureCode
                ));

        MvcResult result = assertErrorTrace(
                performValid(KEY, traceId),
                500,
                "INTERNAL_ERROR",
                traceId
        ).andReturn();

        if (failureCode != null && !failureCode.isEmpty()) {
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(failureCode);
        }
    }

    private void assertResultError(
            TransactionIntakeResult intakeResult,
            String traceId,
            int status,
            String code
    ) throws Exception {
        when(transactionIntakeService.receive(anyString(), any()))
                .thenReturn(intakeResult);

        assertErrorTrace(
                performValid(KEY, traceId),
                status,
                code,
                traceId
        ).andExpect(jsonPath("$.fieldErrors", hasSize(0)));
    }

    private org.springframework.test.web.servlet.ResultActions assertErrorTrace(
            org.springframework.test.web.servlet.ResultActions result,
            int expectedStatus,
            String expectedCode,
            String traceId
    ) throws Exception {
        return result
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private org.springframework.test.web.servlet.ResultActions performValid(
            String key,
            String traceId
    ) throws Exception {
        return perform(key, traceId, validJson());
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            String key,
            String traceId,
            String json
    ) throws Exception {
        return mockMvc.perform(post(PATH)
                .header("Idempotency-Key", key)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private TransactionValidationException validation(
            TransactionValidationType type,
            String field,
            String code
    ) {
        return new TransactionValidationException(
                type,
                field,
                code,
                "validation failed"
        );
    }

    private TransactionIntakeSnapshot snapshot() {
        return new TransactionIntakeSnapshot(
                TRANSACTION_ID,
                TransactionProcessingStatus.RECEIVED,
                null,
                null,
                null,
                null,
                CREATED_AT
        );
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private String validJson() {
        return """
                {
                  "transactionId":"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
                  "transactionType":"ACCOUNT_TRANSFER",
                  "amount":"1250000",
                  "currencyCode":"KRW",
                  "occurredAt":"2026-07-23T01:10:00Z",
                  "externalCustomerRef":"cust_ref_controller",
                  "senderAccountRef":"acct_ref_controller_sender",
                  "recipientAccountRef":"acct_ref_controller_recipient",
                  "channel":"MOBILE_BANKING",
                  "deviceRef":"device_ref_controller"
                }
                """;
    }
}
