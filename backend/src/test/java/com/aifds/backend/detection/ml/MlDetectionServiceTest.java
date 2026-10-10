package com.aifds.backend.detection.ml;

import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MlDetectionServiceTest {
    private final Instant cutoff = Instant.parse("2026-10-10T01:00:00Z");
    private final UUID transactionId = UUID.randomUUID();

    @Test
    void selectsPolicyAtPinnedCutoffBoundary() {
        Instant activation = Instant.parse("2026-10-09T15:00:00Z");
        MlDetectionService service = new MlDetectionService(true, activation,
                "http://127.0.0.1:1", mock(FinancialTransactionRepository.class),
                mock(BehaviorEventRepository.class), new ObjectMapper());
        assertThat(service.appliesAt(activation.minusNanos(1))).isFalse();
        assertThat(service.appliesAt(activation)).isTrue();
    }

    @Test
    void sendsOnlyApprovedFeaturesAndValidatesPinnedResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(200, """
                {"transactionId":"%s","evaluationCutoffAt":"%s",
                 "featureVersion":"%s","scoringPolicyVersion":"%s",
                 "modelVersion":"%s","modelSha256":"%s",
                 "probabilityBasisPoints":7500,"reasonCode":"ML_RISK_SIGNAL"}
                """.formatted(transactionId, cutoff, MlDetectionPolicy.FEATURE_VERSION,
                MlDetectionPolicy.POLICY_VERSION,
                MlDetectionPolicy.MODEL_VERSION, MlDetectionPolicy.MODEL_SHA256), requestBody);
        try {
            MlDetectionService.MlResult result = service(server).infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION);
            assertThat(result.probabilityBasisPoints()).isEqualTo(7500);
            assertThat(result.contribution()).isEqualTo(20);
            var payload = new ObjectMapper().readTree(requestBody.get());
            assertThat(payload.path("amount").asText()).isEqualTo("1000000");
            assertThat(payload.path("scoringPolicyVersion").asText())
                    .isEqualTo(MlDetectionPolicy.POLICY_VERSION);
            assertThat(payload.path("events").size()).isZero();
            assertThat(payload.has("externalCustomerRef")).isFalse();
            assertThat(payload.has("ruleScore")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsModelVersionFailureWithoutInventingScore() throws Exception {
        HttpServer server = server(503,
                "{\"detail\":{\"code\":\"MODEL_VERSION_MISMATCH\"}}",
                new AtomicReference<>());
        try {
            assertThatThrownBy(() -> service(server).infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .isInstanceOf(MlDetectionService.MlDetectionException.class)
                    .hasMessage("ML_MODEL_VERSION_MISMATCH");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsFeatureValidationFailureWithoutInventingScore() throws Exception {
        HttpServer server = server(400, "{\"code\":\"ML_INVALID_REQUEST\"}",
                new AtomicReference<>());
        try {
            assertThatThrownBy(() -> service(server).infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .isInstanceOf(MlDetectionService.MlDetectionException.class)
                    .hasMessage("ML_INVALID_FEATURES");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutFailsWithoutAProbabilityOrContribution() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/ml-inference", exchange -> {
            try {
                Thread.sleep(4000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // The client timed out and closed its connection.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> service(server).infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .isInstanceOf(MlDetectionService.MlDetectionException.class)
                    .hasMessage("ML_TIMEOUT");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsResponseWithDifferentPolicyVersion() throws Exception {
        HttpServer server = server(200, """
                {"transactionId":"%s","evaluationCutoffAt":"%s",
                 "featureVersion":"%s","scoringPolicyVersion":"changed-policy",
                 "modelVersion":"%s","modelSha256":"%s",
                 "probabilityBasisPoints":7500,"reasonCode":"ML_RISK_SIGNAL"}
                """.formatted(transactionId, cutoff, MlDetectionPolicy.FEATURE_VERSION,
                MlDetectionPolicy.MODEL_VERSION, MlDetectionPolicy.MODEL_SHA256),
                new AtomicReference<>());
        try {
            assertThatThrownBy(() -> service(server).infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .isInstanceOf(MlDetectionService.MlDetectionException.class)
                    .hasMessage("ML_INVALID_RESPONSE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNon200OrNonJsonMlSuccessWithoutInventingContribution() throws Exception {
        String validBody = """
                {"transactionId":"%s","evaluationCutoffAt":"%s",
                 "featureVersion":"%s","scoringPolicyVersion":"%s",
                 "modelVersion":"%s","modelSha256":"%s",
                 "probabilityBasisPoints":7500,"reasonCode":"ML_RISK_SIGNAL"}
                """.formatted(transactionId, cutoff, MlDetectionPolicy.FEATURE_VERSION,
                MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION,
                MlDetectionPolicy.MODEL_SHA256);
        for (int status : List.of(201, 202)) {
            assertInvalidResponse(status, validBody, List.of("application/json"));
        }
        assertInvalidResponse(200, validBody, List.of("text/plain"));
        assertInvalidResponse(200, validBody, List.of());
        assertInvalidResponse(200, validBody,
                List.of("application/json", "application/json"));
        assertInvalidResponse(200, "", List.of("application/json"));
    }

    private void assertInvalidResponse(int status, String body, List<String> contentTypes)
            throws Exception {
        HttpServer server = server(status, body, contentTypes, new AtomicReference<>());
        try {
            assertThatThrownBy(() -> service(server).infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .isInstanceOf(MlDetectionService.MlDetectionException.class)
                    .hasMessage("ML_INVALID_RESPONSE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsExplicitlyWhenCutoffWindowExceedsEventLimit() throws Exception {
        HttpServer server = server(200, "{}", new AtomicReference<>());
        try {
            assertThatThrownBy(() -> service(server,
                    Collections.nCopies(1001, mock(BehaviorEvent.class)))
                    .infer(transactionId, cutoff, MlDetectionPolicy.POLICY_VERSION,
                            MlDetectionPolicy.MODEL_VERSION))
                    .isInstanceOf(MlDetectionService.MlDetectionException.class)
                    .hasMessage("ML_EVENT_LIMIT_EXCEEDED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recordsOnlyBoundedOutcomeAndFailureCategory() throws Exception {
        HttpServer server = server(503,
                "{\"detail\":{\"code\":\"MODEL_UNAVAILABLE\"}}",
                new AtomicReference<>());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
            BehaviorEventRepository events = mock(BehaviorEventRepository.class);
            FinancialTransaction transaction = mock(FinancialTransaction.class);
            when(transaction.getOccurredAt()).thenReturn(cutoff);
            when(transaction.getExternalCustomerRef()).thenReturn("synthetic-customer");
            when(transaction.getAmount()).thenReturn(new BigDecimal("1000000"));
            when(transaction.getTransactionType()).thenReturn(TransactionType.ACCOUNT_TRANSFER);
            when(transaction.getChannel()).thenReturn(TransactionChannel.MOBILE_BANKING);
            when(transactions.findByTransactionId(transactionId))
                    .thenReturn(Optional.of(transaction));
            when(events.findForMlEvaluation(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            @SuppressWarnings("unchecked")
            ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(registry);
            MlDetectionService service = new MlDetectionService(true, Instant.EPOCH,
                    "http://127.0.0.1:" + server.getAddress().getPort(), transactions,
                    events, new ObjectMapper().registerModule(new JavaTimeModule()), provider);
            assertThatThrownBy(() -> service.infer(transactionId, cutoff,
                    MlDetectionPolicy.POLICY_VERSION, MlDetectionPolicy.MODEL_VERSION))
                    .hasMessage("ML_MODEL_UNAVAILABLE");
            assertThat(registry.get("finguardops.ml.inference.total")
                    .tags("outcome", "failure", "category", "ML_MODEL_UNAVAILABLE")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("finguardops.ml.inference.duration")
                    .tags("outcome", "failure", "category", "ML_MODEL_UNAVAILABLE")
                    .timer().count()).isEqualTo(1);
        } finally {
            server.stop(0);
            registry.close();
        }
    }

    private MlDetectionService service(HttpServer server) {
        return service(server, List.of());
    }

    private MlDetectionService service(HttpServer server, List<BehaviorEvent> found) {
        FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
        BehaviorEventRepository events = mock(BehaviorEventRepository.class);
        FinancialTransaction transaction = mock(FinancialTransaction.class);
        when(transaction.getOccurredAt()).thenReturn(cutoff);
        when(transaction.getExternalCustomerRef()).thenReturn("synthetic-customer");
        when(transaction.getAmount()).thenReturn(new BigDecimal("1000000"));
        when(transaction.getTransactionType()).thenReturn(TransactionType.ACCOUNT_TRANSFER);
        when(transaction.getChannel()).thenReturn(TransactionChannel.MOBILE_BANKING);
        when(transactions.findByTransactionId(transactionId)).thenReturn(Optional.of(transaction));
        when(events.findForMlEvaluation(any(), any(), any(), any(), any()))
                .thenReturn(found);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new MlDetectionService(true, Instant.EPOCH,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                transactions, events, mapper);
    }

    private HttpServer server(int status, String body, AtomicReference<String> captured)
            throws Exception {
        return server(status, body, List.of("application/json"), captured);
    }

    private HttpServer server(int status, String body, List<String> contentTypes,
                              AtomicReference<String> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/ml-inference", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            contentTypes.forEach(value -> exchange.getResponseHeaders().add("Content-Type", value));
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return server;
    }
}
