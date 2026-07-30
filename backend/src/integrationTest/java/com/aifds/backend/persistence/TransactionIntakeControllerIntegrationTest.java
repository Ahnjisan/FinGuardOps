package com.aifds.backend.persistence;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class TransactionIntakeControllerIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String PATH = "/api/v1/transactions";
    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FinancialTransactionRepository financialTransactionRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void firstHttpRequestStoresEnvelopeAndReplayUsesStored201AndCurrentTrace()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        String key = key("http-replay");
        String firstTrace = "trace_http_first_01";
        String replayTrace = "trace_http_replay_02";
        String requestBody = requestJson(transactionId, "1250000");

        MvcResult first = mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(TraceIdFilter.TRACE_ID_HEADER, firstTrace)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        firstTrace
                ))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.riskLevel").value(nullValue()))
                .andExpect(jsonPath("$.riskResponseOutcome").value(nullValue()))
                .andExpect(jsonPath("$.adoptedDetectionResultId")
                        .value(nullValue()))
                .andExpect(jsonPath("$.caseId").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(firstTrace))
                .andReturn();

        JsonNode firstBody = responseBody(first);
        assertThat(fieldNames(firstBody)).containsExactlyInAnyOrder(
                "transactionId",
                "processingStatus",
                "riskLevel",
                "riskResponseOutcome",
                "adoptedDetectionResultId",
                "caseId",
                "createdAt",
                "traceId"
        );
        assertThat(financialTransactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);

        IdempotencyRecord record = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        JsonNode storedSnapshot = record.getResponseSnapshot();
        assertThat(storedSnapshot.isObject()).isTrue();
        assertThat(fieldNames(storedSnapshot)).containsExactlyInAnyOrder(
                "responseBody",
                "httpStatus",
                "responseSchemaVersion",
                "codecVersion",
                "finalizedAt"
        );
        assertThat(storedSnapshot.get("httpStatus").intValue())
                .isEqualTo(201);
        assertThat(storedSnapshot.get("responseSchemaVersion").textValue())
                .isEqualTo("transaction-create-response-v1");
        assertThat(storedSnapshot.get("codecVersion").textValue())
                .isEqualTo("transaction-intake-snapshot-envelope-v1");
        assertThat(Instant.parse(
                storedSnapshot.get("finalizedAt").textValue()
        )).isEqualTo(record.getFinishedAt());
        JsonNode storedBody = storedSnapshot.get("responseBody");
        assertThat(fieldNames(storedBody)).containsExactlyInAnyOrder(
                "transactionId",
                "processingStatus",
                "riskLevel",
                "riskResponseOutcome",
                "adoptedDetectionResultId",
                "caseId",
                "createdAt"
        );
        assertThat(containsFieldRecursively(storedSnapshot, "traceId"))
                .isFalse();
        assertThat(containsFieldRecursively(
                storedSnapshot,
                "idempotencyRecordId"
        )).isFalse();
        assertThat(containsFieldRecursively(storedSnapshot, "fingerprint"))
                .isFalse();
        assertThat(containsFieldRecursively(
                storedSnapshot,
                "externalCustomerRef"
        )).isFalse();
        assertThat(containsFieldRecursively(
                storedSnapshot,
                "senderAccountRef"
        )).isFalse();
        assertThat(containsFieldRecursively(
                storedSnapshot,
                "recipientAccountRef"
        )).isFalse();

        MvcResult replay = mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(TraceIdFilter.TRACE_ID_HEADER, replayTrace)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        replayTrace
                ))
                .andExpect(jsonPath("$.traceId").value(replayTrace))
                .andReturn();

        JsonNode replayBody = responseBody(replay);
        assertThat(replayBody.get("transactionId"))
                .isEqualTo(firstBody.get("transactionId"));
        assertThat(replayBody.get("processingStatus"))
                .isEqualTo(firstBody.get("processingStatus"));
        assertThat(replayBody.get("riskLevel"))
                .isEqualTo(firstBody.get("riskLevel"));
        assertThat(replayBody.get("riskResponseOutcome"))
                .isEqualTo(firstBody.get("riskResponseOutcome"));
        assertThat(replayBody.get("adoptedDetectionResultId"))
                .isEqualTo(firstBody.get("adoptedDetectionResultId"));
        assertThat(replayBody.get("caseId"))
                .isEqualTo(firstBody.get("caseId"));
        assertThat(replayBody.get("createdAt"))
                .isEqualTo(firstBody.get("createdAt"));
        assertThat(replayBody.get("traceId").textValue())
                .isEqualTo(replayTrace);
        assertThat(financialTransactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
    }

    @Test
    void legacyCompletedReplayKeeps200AndDoesNotRewriteSnapshot()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        String key = key("http-legacy-replay");
        String requestBody = requestJson(transactionId, "1250000");
        postCreated(key, "trace_legacy_seed_01", requestBody);

        IdempotencyRecord seeded = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        JsonNode legacySnapshot =
                seeded.getResponseSnapshot().get("responseBody").deepCopy();
        jdbcTemplate.update(
                """
                UPDATE idempotency_record
                SET response_snapshot = CAST(? AS jsonb)
                WHERE id = ?
                """,
                legacySnapshot.toString(),
                seeded.getId()
        );

        String replayTrace = "trace_legacy_replay_02";
        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(TraceIdFilter.TRACE_ID_HEADER, replayTrace)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        replayTrace
                ))
                .andExpect(jsonPath("$.transactionId")
                        .value(transactionId.toString()))
                .andExpect(jsonPath("$.processingStatus")
                        .value("RECEIVED"))
                .andExpect(jsonPath("$.traceId").value(replayTrace));

        IdempotencyRecord replayed = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(replayed.getResponseSnapshot()).isEqualTo(legacySnapshot);
        assertThat(financialTransactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
    }

    @Test
    void corruptedCompletedEnvelopeReturnsInternalErrorWithoutNewTransaction()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        String key = key("http-corrupt-envelope");
        String requestBody = requestJson(transactionId, "1250000");
        postCreated(key, "trace_corrupt_seed_01", requestBody);

        IdempotencyRecord seeded = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        ObjectNode corrupted =
                (ObjectNode) seeded.getResponseSnapshot();
        corrupted.put(
                "codecVersion",
                "transaction-intake-snapshot-envelope-v999"
        );
        jdbcTemplate.update(
                """
                UPDATE idempotency_record
                SET response_snapshot = CAST(? AS jsonb)
                WHERE id = ?
                """,
                corrupted.toString(),
                seeded.getId()
        );

        String replayTrace = "trace_corrupt_replay_02";
        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(TraceIdFilter.TRACE_ID_HEADER, replayTrace)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        replayTrace
                ))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").value(replayTrace));

        assertThat(financialTransactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
        IdempotencyRecord afterFailure = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
        assertThat(afterFailure.getResponseSnapshot()).isEqualTo(corrupted);
        assertThat(afterFailure.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
    }

    @Test
    void sameKeyDifferentFingerprintReturns409WithoutAdditionalRows()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        String key = key("http-fingerprint");
        postCreated(key, "trace_fingerprint_first_01",
                requestJson(transactionId, "1250000"));

        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                "trace_fingerprint_conflict_02"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(transactionId, "1250001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.traceId")
                        .value("trace_fingerprint_conflict_02"))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        "trace_fingerprint_conflict_02"
                ));

        assertThat(financialTransactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);
    }

    @Test
    void differentKeySameTransactionIdReturnsDuplicateAndFailedClaim()
            throws Exception {
        UUID transactionId = UUID.randomUUID();
        String firstKey = key("http-original");
        String duplicateKey = key("http-duplicate");
        String requestBody = requestJson(transactionId, "1250000");
        postCreated(firstKey, "trace_duplicate_first_01", requestBody);

        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", duplicateKey)
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                "trace_duplicate_second_02"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("DUPLICATE_TRANSACTION"))
                .andExpect(jsonPath("$.traceId")
                        .value("trace_duplicate_second_02"))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        "trace_duplicate_second_02"
                ));

        assertThat(financialTransactionRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        IdempotencyRecord duplicateRecord = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(
                        OPERATION_SCOPE,
                        duplicateKey
                )
                .orElseThrow();
        assertThat(duplicateRecord.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(duplicateRecord.getFailureCode())
                .isEqualTo("DUPLICATE_TRANSACTION");
    }

    private void postCreated(
            String key,
            String traceId,
            String requestBody
    ) throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());
    }

    private JsonNode responseBody(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private boolean containsFieldRecursively(JsonNode node, String fieldName) {
        if (node.isObject()) {
            if (node.has(fieldName)) {
                return true;
            }
            for (JsonNode value : node) {
                if (containsFieldRecursively(value, fieldName)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsFieldRecursively(value, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String requestJson(UUID transactionId, String amount)
            throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("transactionId", transactionId.toString());
        request.put("transactionType", "ACCOUNT_TRANSFER");
        request.put("amount", amount);
        request.put("currencyCode", "KRW");
        request.put(
                "occurredAt",
                Instant.now()
                        .minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS)
                        .toString()
        );
        request.put("externalCustomerRef", "cust_ref_http_integration");
        request.put(
                "senderAccountRef",
                "acct_ref_http_integration_sender"
        );
        request.put(
                "recipientAccountRef",
                "acct_ref_http_integration_recipient"
        );
        request.put("channel", "MOBILE_BANKING");
        request.put("deviceRef", "device_ref_http_integration");
        return objectMapper.writeValueAsString(request);
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
