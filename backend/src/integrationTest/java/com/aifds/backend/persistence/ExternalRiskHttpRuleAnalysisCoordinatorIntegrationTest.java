package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.service.CompletedRuleAnalysis;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.client.ExternalRiskHttpAdapter;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.TransactionSynchronousProcessingCoordinator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles({"test", "external-risk-http"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "finguardops.external-risk.http.enabled=true",
                "finguardops.external-risk.http.api-key=integration-test-key"
        }
)
class ExternalRiskHttpRuleAnalysisCoordinatorIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String TRACE_ID = "trace-external-http-integration-0001";
    private static final AtomicInteger PROVIDER_CALLS = new AtomicInteger();
    private static final HttpServer PROVIDER = startProvider();

    @Autowired
    private ExternalRiskRuleAnalysisCoordinator coordinator;

    @Autowired
    private TransactionSynchronousProcessingCoordinator
            synchronousProcessingCoordinator;

    @Autowired
    private ExternalRiskPolicyService policyService;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @MockitoSpyBean
    private ExternalRiskHttpAdapter httpAdapter;

    @MockitoBean
    private RuleAnalysisOrchestrationService orchestrationService;

    @DynamicPropertySource
    static void registerProvider(DynamicPropertyRegistry registry) {
        registry.add(
                "finguardops.external-risk.http.base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort()
        );
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop(0);
    }

    @Test
    void createsRealWorkflowBeansAndCallsProviderOnceOutsideTransaction() {
        FinancialTransaction transaction = saveTransaction();
        CompletedRuleAnalysis expected = new CompletedRuleAnalysis(
                transaction.getTransactionId(),
                UUID.randomUUID(),
                1,
                15,
                RiskLevel.MEDIUM
        );
        when(orchestrationService.analyzeV2(
                eq(transaction.getTransactionId()),
                any(),
                anyString()
        )).thenReturn(expected);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager
                    .isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(httpAdapter).lookup(any());
        PROVIDER_CALLS.set(0);

        CompletedRuleAnalysis actual = coordinator.analyzeWithExternalRisk(
                transaction.getTransactionId(),
                TRACE_ID
        );

        assertThat(actual).isEqualTo(expected);
        assertThat(synchronousProcessingCoordinator.isAvailable()).isTrue();
        assertThat(policyService).isNotNull();
        assertThat(transactionActive).isFalse();
        assertThat(PROVIDER_CALLS).hasValue(1);
        verify(httpAdapter, times(1)).lookup(any());
        verify(orchestrationService, times(1)).analyzeV2(
                eq(transaction.getTransactionId()),
                any(),
                eq(TRACE_ID)
        );
    }

    private FinancialTransaction saveTransaction() {
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS),
                "cust_ref_external_http_integration",
                "acct_ref_external_http_sender",
                "acct_ref_external_http_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_external_http_integration"
        ));
    }

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            server.createContext("/v1/external-risk/lookup", event -> {
                PROVIDER_CALLS.incrementAndGet();
                event.getRequestBody().readAllBytes();
                writeSuccess(event);
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void writeSuccess(HttpExchange exchange) throws IOException {
        byte[] body = """
                {
                  "providerCode": "PROVIDER_V1",
                  "providerAsOf": "2026-01-01T00:00:00Z",
                  "matches": []
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }
}
