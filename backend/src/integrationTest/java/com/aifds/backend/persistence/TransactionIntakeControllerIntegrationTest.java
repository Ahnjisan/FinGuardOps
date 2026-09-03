package com.aifds.backend.persistence;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(
        authorities = "transaction:intake"
)
class TransactionIntakeControllerIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String PATH = "/api/v1/transactions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private FinancialTransactionRepository transactionRepository;

    @Test
    void missingProviderReturnsSafe503AndWritesNoRows() throws Exception {
        String traceId = "trace_http_provider_missing_01";
        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key("http-missing"))
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(UUID.randomUUID(), "1250000")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.code")
                        .value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("탐지 서비스를 사용할 수 없습니다."))
                .andExpect(jsonPath("$.traceId").value(traceId));

        assertThat(transactionRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isZero();
    }

    @Test
    void missingProviderPrecedesExistingTerminalReplay() throws Exception {
        UUID transactionId = UUID.randomUUID();
        String key = key("http-terminal");
        IdempotencyClaimResult.Acquired acquired =
                (IdempotencyClaimResult.Acquired) idempotencyService.claim(
                        key,
                        command(transactionId).toFingerprintInput()
                );
        idempotencyService.fail(acquired.recordId(), "DEPENDENCY_TIMEOUT");

        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                "trace_http_terminal_missing_01"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(transactionId, "1250000")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("DEPENDENCY_UNAVAILABLE"));

        assertThat(idempotencyRepository.findById(acquired.recordId())
                .orElseThrow().getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(transactionRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    @Test
    void requestValidationStillReturns400BeforeAvailability() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", "short")
                        .header(
                                TraceIdFilter.TRACE_ID_HEADER,
                                "trace_http_invalid_key_01"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(UUID.randomUUID(), "1250000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(transactionRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isZero();
    }

    private ValidatedTransactionCommand command(UUID transactionId) {
        return new ValidatedTransactionCommand(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-08-27T00:00:00Z"),
                "customer_ref_http_provider_missing",
                "sender_ref_http_provider_missing",
                "recipient_ref_http_provider_missing",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_http_provider_missing"
        );
    }

    private String requestJson(UUID transactionId, String amount)
            throws Exception {
        ValidatedTransactionCommand command = command(transactionId);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("transactionId", transactionId.toString());
        request.put("transactionType", command.transactionType().name());
        request.put("amount", amount);
        request.put("currencyCode", command.currencyCode());
        request.put("occurredAt", command.occurredAt().toString());
        request.put("externalCustomerRef", command.externalCustomerRef());
        request.put("senderAccountRef", command.senderAccountRef());
        request.put("recipientAccountRef", command.recipientAccountRef());
        request.put("channel", command.channel().name());
        request.put("deviceRef", command.deviceRef());
        return objectMapper.writeValueAsString(request);
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
