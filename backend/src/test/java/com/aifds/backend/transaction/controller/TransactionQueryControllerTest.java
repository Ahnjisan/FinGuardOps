package com.aifds.backend.transaction.controller;

import com.aifds.backend.common.error.GlobalExceptionHandler;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.TransactionQueryMapper;
import com.aifds.backend.transaction.service.TransactionQueryService;
import com.aifds.backend.transaction.validation.TransactionQueryValidator;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

@WebMvcTest(TransactionQueryController.class)
@org.springframework.security.test.context.support.WithMockUser
@Import({
        com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        TraceIdFilter.class,
        TransactionQueryService.class,
        TransactionQueryMapper.class,
        TransactionQueryValidator.class
})
class TransactionQueryControllerTest {

    private static final String PATH = "/api/v1/transactions";
    private static final String TRANSACTION_ID =
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
    private static final String TRACE_ID = "trace_query_controller_01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FinancialTransactionRepository repository;

    @BeforeEach
    void resetRepository() {
        reset(repository);
    }

    @Test
    void returnsExactListContractWithDefaultsAmountAndExplicitNull()
            throws Exception {
        FinancialTransaction transaction = transaction(null, null);
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(transaction),
                PageRequest.of(0, 20),
                1
        ));

        MvcResult result = mockMvc.perform(get(PATH)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.content[0].amount").value("1250.5"))
                .andExpect(jsonPath("$.content[0].recipientAccountRef")
                        .value(nullValue()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true))
                .andReturn();

        JsonNode body = responseBody(result);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "content", "page", "traceId"
        );
        assertThat(fieldNames(body.get("content").get(0)))
                .containsExactlyInAnyOrder(
                        "transactionId",
                        "transactionType",
                        "amount",
                        "currencyCode",
                        "occurredAt",
                        "externalCustomerRef",
                        "senderAccountRef",
                        "recipientAccountRef",
                        "processingStatus",
                        "createdAt"
                );
        assertThat(fieldNames(body.get("page"))).containsExactlyInAnyOrder(
                "number",
                "size",
                "totalElements",
                "totalPages",
                "first",
                "last"
        );
        assertExcludedFields(body.toString());
    }

    @Test
    void returnsExactDetailContractWithExplicitNullableFields()
            throws Exception {
        UUID id = UUID.fromString(TRANSACTION_ID);
        FinancialTransaction transaction = transaction(null, null);
        when(repository.findByTransactionId(id))
                .thenReturn(Optional.of(transaction));

        MvcResult result = mockMvc.perform(get(PATH + "/" + TRANSACTION_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.transaction.amount").value("1250.5"))
                .andExpect(jsonPath("$.transaction.recipientAccountRef")
                        .value(nullValue()))
                .andExpect(jsonPath("$.transaction.deviceRef")
                        .value(nullValue()))
                .andReturn();

        JsonNode body = responseBody(result);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "transaction", "traceId"
        );
        assertThat(fieldNames(body.get("transaction")))
                .containsExactlyInAnyOrder(
                        "transactionId",
                        "transactionType",
                        "amount",
                        "currencyCode",
                        "occurredAt",
                        "externalCustomerRef",
                        "senderAccountRef",
                        "recipientAccountRef",
                        "channel",
                        "deviceRef",
                        "processingStatus",
                        "createdAt",
                        "updatedAt"
                );
        assertExcludedFields(body.toString());
        assertThat(body.toString()).doesNotContain(
                "\"id\"",
                "\"version\"",
                "idempotency",
                "fingerprint",
                "failureCode"
        );
    }

    @Test
    void acceptsOneSidedAndEqualDateRangesAndReturnsEmptyPage()
            throws Exception {
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(),
                PageRequest.of(0, 20),
                0
        ));

        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtFrom",
                                "2026-07-23T00:00:00Z"
                        ))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtTo",
                                "2026-07-24T00:00:00Z"
                        ))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtFrom",
                                "2026-07-23T00:00:00Z"
                        )
                        .queryParam(
                                "occurredAtTo",
                                "2026-07-23T00:00:00Z"
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true));
    }

    @Test
    void mapsFormatValidationCasesToSafe400() throws Exception {
        assertValidationStatus("occurredAtFrom",
                "2026-07-23T09:00:00+09:00", 400);
        assertValidationStatus("occurredAtFrom", "not-a-date", 400);
        assertValidationStatus("transactionType", "account_transfer", 400);
        assertValidationStatus("processingStatus", "received", 400);
        assertValidationStatus("externalCustomerRef", " ", 400);
        assertValidationStatus("accountRef", "\t", 400);
        assertValidationStatus("page", "one", 400);
        assertValidationStatus("size", "1.5", 400);
        assertValidationStatus("sort", "createdAt,desc", 400);
        assertValidationStatus("sort", "occurredAt,DESC", 400);
        assertValidationStatus("sort", "occurredAt", 400);

        mockMvc.perform(get(PATH)
                        .queryParam(
                                "sort",
                                "occurredAt,desc",
                                "occurredAt,asc"
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get(PATH)
                        .queryParam(
                                "transactionType",
                                "ACCOUNT_TRANSFER",
                                "ATM_WITHDRAWAL"
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapsDomainValidationCasesToSafe422() throws Exception {
        assertValidationStatus("page", "-1", 422);
        assertValidationStatus("size", "0", 422);
        assertValidationStatus("size", "101", 422);

        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtFrom",
                                "2026-07-24T00:00:00Z"
                        )
                        .queryParam(
                                "occurredAtTo",
                                "2026-07-23T00:00:00Z"
                        ))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapsInvalidAndMissingTransactionIdsTo400And404()
            throws Exception {
        for (String invalid : new String[]{
                "not-a-uuid",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "2f4c0a4e-8a9d-4c2f-7a1b-7d6e5f430001"
        }) {
            mockMvc.perform(get(PATH + "/" + invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("VALIDATION_ERROR"));
        }

        when(repository.findByTransactionId(UUID.fromString(TRANSACTION_ID)))
                .thenReturn(Optional.empty());
        mockMvc.perform(get(PATH + "/" + TRANSACTION_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("요청한 거래를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void mapsWhitelistedRepositoryFailuresAndUnknownFailuresSafely()
            throws Exception {
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new QueryTimeoutException(
                "select secret_table raw timeout"
        ));
        assertSafeFailure(
                503,
                "DEPENDENCY_TIMEOUT",
                "조회 요청이 제한 시간 안에 완료되지 않았습니다."
        );

        reset(repository);
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new DataAccessResourceFailureException(
                "connection to secret_database failed"
        ));
        assertSafeFailure(
                503,
                "DEPENDENCY_UNAVAILABLE",
                "조회 저장소를 일시적으로 사용할 수 없습니다."
        );

        reset(repository);
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new DataIntegrityViolationException(
                "secret_table.secret_column"
        ));
        assertSafeFailure(
                500,
                "INTERNAL_ERROR",
                "요청을 처리하는 중 오류가 발생했습니다."
        );

        reset(repository);
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new RuntimeException("raw runtime details"));
        assertSafeFailure(
                500,
                "INTERNAL_ERROR",
                "요청을 처리하는 중 오류가 발생했습니다."
        );
    }

    private void assertValidationStatus(
            String parameter,
            String value,
            int expectedStatus
    ) throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam(parameter, value)
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    private void assertSafeFailure(
            int expectedStatus,
            String expectedCode,
            String expectedMessage
    ) throws Exception {
        MvcResult result = mockMvc.perform(get(PATH)
                        .queryParam(
                                "externalCustomerRef",
                                "sensitive_customer_ref"
                        )
                        .header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        TRACE_ID
                ))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "secret_table",
                        "secret_column",
                        "secret_database",
                        "sensitive_customer_ref",
                        "raw runtime details"
                );
    }

    private FinancialTransaction transaction(
            String recipientAccountRef,
            String deviceRef
    ) {
        FinancialTransaction transaction = org.mockito.Mockito.mock(
                FinancialTransaction.class
        );
        when(transaction.getTransactionId())
                .thenReturn(UUID.fromString(TRANSACTION_ID));
        when(transaction.getTransactionType())
                .thenReturn(TransactionType.ACCOUNT_TRANSFER);
        when(transaction.getAmount())
                .thenReturn(new BigDecimal("1250.5000"));
        when(transaction.getCurrencyCode()).thenReturn("KRW");
        when(transaction.getOccurredAt())
                .thenReturn(Instant.parse("2026-07-23T01:15:30Z"));
        when(transaction.getExternalCustomerRef())
                .thenReturn("cust_ref_query");
        when(transaction.getSenderAccountRef())
                .thenReturn("acct_ref_query_sender");
        when(transaction.getRecipientAccountRef())
                .thenReturn(recipientAccountRef);
        when(transaction.getChannel())
                .thenReturn(TransactionChannel.MOBILE_BANKING);
        when(transaction.getDeviceRef()).thenReturn(deviceRef);
        when(transaction.getProcessingStatus())
                .thenReturn(TransactionProcessingStatus.RECEIVED);
        when(transaction.getCreatedAt())
                .thenReturn(Instant.parse("2026-07-23T01:15:31Z"));
        when(transaction.getUpdatedAt())
                .thenReturn(Instant.parse("2026-07-23T01:15:32Z"));
        return transaction;
    }

    private JsonNode responseBody(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertExcludedFields(String json) {
        assertThat(json).doesNotContain(
                "riskLevel",
                "riskResponseOutcome",
                "adoptedDetectionResultId",
                "adoptedDetectionResult",
                "activeCaseLinked",
                "activeCaseSummary",
                "hasCaseHistory",
                "caseHistoryCount",
                "behaviorEventSummary"
        );
    }
}
