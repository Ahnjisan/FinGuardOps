package com.aifds.backend.rule.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.rule.client.config.AiServiceProperties;
import com.aifds.backend.rule.client.config.RuleAnalysisClientConfiguration;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleAnalysisResultResponse;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static com.aifds.backend.rule.client.RuleAnalysisClientTestFixtures.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleAnalysisHttpClientTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI baseUrl;
    private ObjectMapper mapper;
    private RuleAnalysisRequest request;
    private RuleAnalysisRequestV2 requestV2;
    private final AtomicReference<ResponseSpec> response = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private final AtomicReference<List<String>> requestTraceHeaders =
            new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<List<String>> requestContentTypes =
            new AtomicReference<>();
    private final AtomicReference<List<String>> requestAccepts =
            new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicBoolean responseBodyPrefixFlushed = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        RuleAnalysisClientConfiguration configuration =
                new RuleAnalysisClientConfiguration();
        mapper = configuration.ruleAnalysisObjectMapper(
                new Jackson2ObjectMapperBuilder()
        );
        request = RuleAnalysisClientTestFixtures.request(mapper);
        requestV2 = RuleAnalysisClientTestFixtures.requestV2(mapper);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RuleAnalysisHttpClient.ENDPOINT, this::handle);
        server.createContext(RuleAnalysisHttpClient.V2_ENDPOINT, this::handle);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void performsOneSynchronousRequestWithStrictSerializationAndTrace() throws Exception {
        response.set(jsonResponse(
                200,
                mapper.writeValueAsString(
                        RuleAnalysisClientTestFixtures.matchedResponse(mapper)
                )
        ));

        RuleAnalysisResponse actual = client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID);

        assertThat(actual.analysis().scoringResult().riskScore()).isEqualTo(25);
        assertThat(requestCount).hasValue(1);
        assertThat(requestMethod).hasValue("POST");
        assertThat(requestPath).hasValue("/api/v1/rule-analysis");
        assertThat(requestTraceHeaders.get()).containsExactly(TRACE_ID);
        JsonNode serialized = mapper.readTree(requestBody.get());
        assertThat(serialized.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "evaluationCutoffAt",
                        "transaction",
                        "behaviorEvents",
                        "ruleVersions"
                );
        assertThat(serialized.path("transaction").path("transactionId").asText())
                .isEqualTo(request.transaction().transactionId().toString());
        assertThat(serialized.path("transaction").path("occurredAt").asText())
                .isEqualTo("2026-07-23T12:00:00Z");
        assertThat(serialized.path("behaviorEvents").get(0).has("deviceRef"))
                .isTrue();
        assertThat(serialized.path("behaviorEvents").get(0).get("deviceRef").isNull())
                .isTrue();
        assertThat(serialized.path("ruleVersions").get(0).has("effectiveTo"))
                .isTrue();
        assertThat(serialized.path("ruleVersions").get(0).get("effectiveTo").isNull())
                .isTrue();
    }

    @Test
    void performsOneV2RequestWithTheExactExternalRiskWireContract()
            throws Exception {
        response.set(jsonResponse(
                200,
                mapper.writeValueAsString(
                        RuleAnalysisClientTestFixtures.matchedResponse(mapper)
                )
        ));

        RuleAnalysisResponse actual = client(Duration.ofSeconds(1))
                .analyzeV2(requestV2, TRACE_ID);

        assertThat(actual.analysis().scoringResult().riskScore()).isEqualTo(25);
        assertThat(requestCount).hasValue(1);
        assertThat(requestMethod).hasValue("POST");
        assertThat(requestPath).hasValue("/api/v2/rule-analysis");
        assertThat(requestTraceHeaders.get()).containsExactly(TRACE_ID);
        assertThat(requestContentTypes.get()).containsExactly("application/json");
        assertThat(requestAccepts.get()).containsExactly("application/json");
        JsonNode serialized = mapper.readTree(requestBody.get());
        JsonNode externalRisk = serialized.path("externalRisk");
        assertThat(externalRisk.fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "providerCode",
                        "lookupStatus",
                        "policyResult",
                        "providerAsOf",
                        "lookedUpAt",
                        "matches"
                );
        assertThat(externalRisk.path("policyResult").asText())
                .isEqualTo("MATCHED");
        assertThat(externalRisk.has("result")).isFalse();
        assertThat(externalRisk.path("matches").get(0)
                .path("externalRiskType").asText())
                .isEqualTo("SUSPICIOUS_ACCOUNT");
    }

    @Test
    void acceptsTheValidAllRulesUnmatchedResult() throws Exception {
        response.set(jsonResponse(
                200,
                mapper.writeValueAsString(
                        RuleAnalysisClientTestFixtures.unmatchedResponse()
                )
        ));

        RuleAnalysisResponse actual = client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID);

        assertThat(actual.analysis().scoringResult().riskScore()).isZero();
        assertThat(actual.analysis().scoringResult().riskLevel().name())
                .isEqualTo("LOW");
        assertThat(actual.analysis().evidence()).isEmpty();
    }

    @Test
    void rejectsWellFormedRuleSetVersionThatDoesNotMatchRequest()
            throws Exception {
        RuleAnalysisResponse valid =
                RuleAnalysisClientTestFixtures.matchedResponse(mapper);
        RuleAnalysisResponse mismatched = new RuleAnalysisResponse(
                valid.transactionId(),
                valid.traceId(),
                new RuleAnalysisResultResponse(
                        valid.analysis().evaluationCutoffAt(),
                        "0".repeat(64),
                        valid.analysis().scoringResult(),
                        valid.analysis().evidence()
                )
        );
        response.set(jsonResponse(200, mapper.writeValueAsString(mismatched)));

        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));
        assertThat(requestCount).hasValue(1);
    }

    @ParameterizedTest
    @MethodSource("mappedErrors")
    void mapsOnlyConsistentFastApiErrorEnvelopes(
            int status,
            String code,
            RuleAnalysisClientErrorCategory expected
    ) {
        response.set(jsonResponse(status, errorJson(code, TRACE_ID)));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(expected);
                            assertThat(exception.httpStatus()).hasValue(status);
                            assertThat(exception.getMessage())
                                    .doesNotContain("upstream sensitive message");
                        }
                );
        assertThat(requestCount).hasValue(1);
    }

    @ParameterizedTest
    @MethodSource("mappedErrors")
    void mapsTheSameApprovedFastApiErrorsForV2(
            int status,
            String code,
            RuleAnalysisClientErrorCategory expected
    ) {
        response.set(jsonResponse(status, errorJson(code, TRACE_ID)));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyzeV2(requestV2, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(expected);
                            assertThat(exception.httpStatus()).hasValue(status);
                        }
                );
        assertThat(requestCount).hasValue(1);
        assertThat(requestPath).hasValue("/api/v2/rule-analysis");
    }

    @Test
    void appliesTheExistingResponseValidatorToV2() throws Exception {
        RuleAnalysisResponse valid =
                RuleAnalysisClientTestFixtures.matchedResponse(mapper);
        RuleAnalysisResponse mismatched = new RuleAnalysisResponse(
                valid.transactionId(),
                valid.traceId(),
                new RuleAnalysisResultResponse(
                        valid.analysis().evaluationCutoffAt(),
                        "0".repeat(64),
                        valid.analysis().scoringResult(),
                        valid.analysis().evidence()
                )
        );
        String rawResponse = mapper.writeValueAsString(mismatched);
        response.set(jsonResponse(200, rawResponse));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyzeV2(requestV2, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_INVALID_RESPONSE
                            );
                            assertThat(exception.httpStatus()).hasValue(200);
                            assertSafeException(
                                    exception,
                                    rawResponse,
                                    "EXTERNAL_RISK_MOCK_V1",
                                    "customer_sensitive_ref"
                            );
                        }
                );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void doesNotPerformV2HttpRequestWhenMappingFails() {
        ExternalRiskSnapshot valid =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot();
        ExternalRiskSnapshot mismatched = new ExternalRiskSnapshot(
                valid.transactionId(),
                valid.evaluationCutoffAt().plusSeconds(1),
                valid.lookedUpAt().plusSeconds(2),
                valid.providerCode(),
                valid.providerAsOf(),
                valid.lookupStatus(),
                valid.policyResult(),
                valid.matches()
        );
        RuleAnalysisRequestV2Mapper requestMapper =
                new RuleAnalysisRequestV2Mapper();

        assertThatThrownBy(() -> {
            RuleAnalysisRequestV2 mapped = requestMapper.map(
                    request,
                    mismatched
            );
            client(Duration.ofSeconds(1)).analyzeV2(mapped, TRACE_ID);
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("snapshot evaluationCutoffAt must match request");

        assertThat(requestCount).hasValue(0);
    }

    @Test
    void mapsV2ResponseTimeoutWithoutRetryOrInformationExposure() {
        String rawResponse = "provider_response_secret";
        response.set(new ResponseSpec(
                200,
                "application/json",
                List.of(TRACE_ID),
                rawResponse,
                Duration.ofMillis(300)
        ));

        assertThatThrownBy(() -> client(Duration.ofMillis(50))
                .analyzeV2(requestV2, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_RESPONSE_TIMEOUT
                            );
                            assertThat(exception.httpStatus()).isEmpty();
                            assertSafeException(
                                    exception,
                                    rawResponse,
                                    baseUrl.toString(),
                                    "EXTERNAL_RISK_MOCK_V1",
                                    "customer_sensitive_ref"
                            );
                        }
                );

        assertThat(requestCount).hasValue(1);
    }

    @Test
    void mapsV2ConnectFailureAsUnavailableWithoutRetryOrInformationExposure() {
        String marker = "v2_connect_failure_secret";
        String originalMessage = "connect failure at " + baseUrl + " " + marker;
        AtomicInteger attempts = new AtomicInteger();
        ClientHttpRequestFactory failingRequestFactory = (uri, method) -> {
            attempts.incrementAndGet();
            throw new ResourceAccessException(
                    originalMessage,
                    new ConnectException(marker)
            );
        };
        RestClient failingRestClient = RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(failingRequestFactory)
                .build();
        RuleAnalysisHttpClient failingClient = new RuleAnalysisHttpClient(
                failingRestClient,
                mapper,
                new RuleAnalysisResponseValidator(),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> failingClient.analyzeV2(requestV2, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_UNAVAILABLE
                            );
                            assertThat(exception.httpStatus()).isEmpty();
                            assertSafeException(
                                    exception,
                                    marker,
                                    originalMessage,
                                    baseUrl.toString(),
                                    "EXTERNAL_RISK_MOCK_V1",
                                    "customer_sensitive_ref"
                            );
                        }
                );

        assertThat(attempts).hasValue(1);
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void rejectsMalformedV2JsonWithoutRetryOrInformationExposure() {
        String rawResponse = "{\"transactionId\":\"provider_body_secret\"";
        response.set(jsonResponse(200, rawResponse));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyzeV2(requestV2, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_INVALID_RESPONSE
                            );
                            assertThat(exception.httpStatus()).hasValue(200);
                            assertSafeException(
                                    exception,
                                    rawResponse,
                                    "provider_body_secret",
                                    "EXTERNAL_RISK_MOCK_V1",
                                    "customer_sensitive_ref"
                            );
                        }
                );

        assertThat(requestCount).hasValue(1);
    }

    private static Stream<Arguments> mappedErrors() {
        return Stream.of(
                Arguments.of(
                        400,
                        "INVALID_REQUEST",
                        RuleAnalysisClientErrorCategory
                                .AI_SERVICE_REQUEST_CONTRACT_ERROR
                ),
                Arguments.of(
                        413,
                        "PAYLOAD_TOO_LARGE",
                        RuleAnalysisClientErrorCategory
                                .AI_SERVICE_PAYLOAD_TOO_LARGE
                ),
                Arguments.of(
                        422,
                        "RULE_CONTRACT_ERROR",
                        RuleAnalysisClientErrorCategory
                                .AI_SERVICE_RULE_CONTRACT_ERROR
                ),
                Arguments.of(
                        500,
                        "UNSUPPORTED_RULE_CAPABILITY",
                        RuleAnalysisClientErrorCategory
                                .AI_SERVICE_CAPABILITY_MISMATCH
                ),
                Arguments.of(
                        500,
                        "INTERNAL_ERROR",
                        RuleAnalysisClientErrorCategory.AI_SERVICE_INTERNAL_ERROR
                )
        );
    }

    @Test
    void rejectsUnsupportedStatusAndDoesNotRetry() {
        response.set(jsonResponse(503, errorJson("INTERNAL_ERROR", TRACE_ID)));

        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        assertThat(requestCount).hasValue(1);
    }

    @Test
    void rejectsContradictoryAndStructurallyInvalidErrorEnvelopes() throws Exception {
        response.set(jsonResponse(
                400,
                errorJson("RULE_CONTRACT_ERROR", TRACE_ID)
        ));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        ObjectNode unknown = (ObjectNode) mapper.readTree(
                errorJson("INVALID_REQUEST", TRACE_ID)
        );
        unknown.put("upstreamDetail", "must not be accepted");
        response.set(jsonResponse(400, mapper.writeValueAsString(unknown)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        ObjectNode missing = (ObjectNode) mapper.readTree(
                errorJson("INVALID_REQUEST", TRACE_ID)
        );
        missing.remove("fieldErrors");
        response.set(jsonResponse(400, mapper.writeValueAsString(missing)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));
    }

    @Test
    void rejectsContentTypeMismatchMalformedAndTruncatedJson() throws Exception {
        String valid = mapper.writeValueAsString(
                RuleAnalysisClientTestFixtures.matchedResponse(mapper)
        );
        response.set(new ResponseSpec(
                200,
                "text/plain",
                List.of(TRACE_ID),
                valid,
                Duration.ZERO
        ));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        response.set(jsonResponse(200, "{\"transactionId\":"));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));
    }

    @Test
    void rejectsUnknownFieldsAndMissingRequiredFields() throws Exception {
        ObjectNode unknown = validResponseTree();
        unknown.put("unexpected", "value");
        response.set(jsonResponse(200, mapper.writeValueAsString(unknown)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        ObjectNode missing = validResponseTree();
        missing.remove("analysis");
        response.set(jsonResponse(200, mapper.writeValueAsString(missing)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        ObjectNode coerced = validResponseTree();
        coerced.withObject("analysis")
                .withObject("scoringResult")
                .put("riskScore", "25");
        response.set(jsonResponse(200, mapper.writeValueAsString(coerced)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));
    }

    @Test
    void rejectsMissingMultipleAndMismatchedTraceValues() throws Exception {
        String valid = mapper.writeValueAsString(
                RuleAnalysisClientTestFixtures.matchedResponse(mapper)
        );
        response.set(new ResponseSpec(
                200,
                "application/json",
                List.of(),
                valid,
                Duration.ZERO
        ));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        response.set(new ResponseSpec(
                200,
                "application/json",
                List.of(TRACE_ID, TRACE_ID),
                valid,
                Duration.ZERO
        ));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));

        ObjectNode bodyMismatch = validResponseTree();
        bodyMismatch.put("traceId", "trace_rule_client_other");
        response.set(jsonResponse(200, mapper.writeValueAsString(bodyMismatch)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));
    }

    @Test
    void rejectsTransactionCutoffContributionGroupAndEvidenceContradictions()
            throws Exception {
        ObjectNode transactionMismatch = validResponseTree();
        transactionMismatch.put(
                "transactionId",
                "10000000-0000-4000-8000-000000000002"
        );
        assertInvalidTree(transactionMismatch);

        ObjectNode cutoffMismatch = validResponseTree();
        cutoffMismatch.withObject("analysis")
                .put("evaluationCutoffAt", "2026-07-23T12:00:01Z");
        assertInvalidTree(cutoffMismatch);

        ObjectNode contributionMismatch = validResponseTree();
        ((ObjectNode) contributions(contributionMismatch).get(0))
                .put("originalContribution", 14);
        assertInvalidTree(contributionMismatch);

        ObjectNode groupMismatch = validResponseTree();
        ((ObjectNode) groupSummaries(groupMismatch).get(1))
                .put("appliedScore", 9);
        assertInvalidTree(groupMismatch);

        ObjectNode evidenceMismatch = validResponseTree();
        evidence(evidenceMismatch).remove(1);
        assertInvalidTree(evidenceMismatch);
    }

    @Test
    void rejectsObservationAllowlistAndReferencedEventMismatches() throws Exception {
        ObjectNode unknownObservation = validResponseTree();
        ((ObjectNode) evidence(unknownObservation).get(1)
                .path("observationSummary"))
                .put("recipientAccountRef", "forbidden_sensitive_value");
        assertInvalidTree(unknownObservation);

        ObjectNode unknownEvent = validResponseTree();
        ((ObjectNode) evidence(unknownEvent).get(1)
                .path("observationSummary"))
                .put("eventId", "30000000-0000-4000-8000-000000000099");
        assertInvalidTree(unknownEvent);
    }

    @Test
    void mapsResponseHeaderWaitTimeoutAndNeverReturnsANormalResult() {
        response.set(new ResponseSpec(
                200,
                "application/json",
                List.of(TRACE_ID),
                "{}",
                Duration.ofMillis(300)
        ));

        assertThatThrownBy(() -> client(Duration.ofMillis(50))
                .analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> assertThat(exception.category()).isEqualTo(
                                RuleAnalysisClientErrorCategory
                                        .AI_SERVICE_RESPONSE_TIMEOUT
                        )
                );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void mapsResponseBodyReadTimeoutWithoutRetryOrInformationExposure()
            throws Exception {
        String rawResponse = mapper.writeValueAsString(
                RuleAnalysisClientTestFixtures.matchedResponse(mapper)
        );
        response.set(partialJsonResponse(
                rawResponse,
                Duration.ofSeconds(2)
        ));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_RESPONSE_TIMEOUT
                            );
                            assertThat(exception.httpStatus()).isEmpty();
                            assertSafeException(
                                    exception,
                                    baseUrl.toString(),
                                    rawResponse,
                                    RuleAnalysisClientTestFixtures.RULE_SET_VERSION
                            );
                        }
                );

        assertThat(requestCount).hasValue(1);
        assertThat(responseBodyPrefixFlushed).isTrue();
    }

    @Test
    void mapsBodyTransportTerminationBeforeTimeoutAsUnavailable()
            throws Exception {
        String rawResponse = mapper.writeValueAsString(
                RuleAnalysisClientTestFixtures.matchedResponse(mapper)
        );
        byte[] responseBytes = rawResponse.getBytes(StandardCharsets.UTF_8);
        AtomicInteger abruptRequestCount = new AtomicInteger();
        AtomicBoolean abruptPrefixFlushed = new AtomicBoolean();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();

        try (ServerSocket abruptServer = new ServerSocket(
                0,
                1,
                InetAddress.getByName("127.0.0.1")
        )) {
            Future<?> serverTask = serverExecutor.submit(() -> {
                try (Socket socket = abruptServer.accept()) {
                    readRequestHeaders(socket.getInputStream());
                    abruptRequestCount.incrementAndGet();
                    socket.setSoLinger(true, 0);
                    OutputStream output = socket.getOutputStream();
                    String headers = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "X-Trace-Id: " + TRACE_ID + "\r\n"
                            + "Content-Length: " + responseBytes.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    output.write(headers.getBytes(StandardCharsets.US_ASCII));
                    output.write(responseBytes, 0, responseBytes.length / 2);
                    output.flush();
                    abruptPrefixFlushed.set(true);
                } catch (Throwable exception) {
                    serverFailure.set(exception);
                }
            });
            URI abruptBaseUrl = URI.create(
                    "http://127.0.0.1:" + abruptServer.getLocalPort()
            );

            assertThatThrownBy(() -> client(
                    abruptBaseUrl,
                    Duration.ofSeconds(5)
            ).analyze(request, TRACE_ID))
                    .isInstanceOfSatisfying(
                            RuleAnalysisClientException.class,
                            exception -> {
                                assertThat(exception.category()).isEqualTo(
                                        RuleAnalysisClientErrorCategory
                                                .AI_SERVICE_UNAVAILABLE
                                );
                                assertThat(exception.httpStatus()).isEmpty();
                                assertSafeException(
                                        exception,
                                        abruptBaseUrl.toString(),
                                        rawResponse,
                                        RuleAnalysisClientTestFixtures
                                                .RULE_SET_VERSION
                                );
                            }
                    );

            serverTask.get(2, TimeUnit.SECONDS);
        }

        assertThat(serverFailure).hasNullValue();
        assertThat(abruptRequestCount).hasValue(1);
        assertThat(abruptPrefixFlushed).isTrue();
    }

    @Test
    void keepsPureJacksonFailureInvalidAfterTheTimeoutBudgetHasElapsed() {
        response.set(jsonResponse(200, "{\"transactionId\":"));
        AtomicInteger tickerCalls = new AtomicInteger();
        LongSupplier elapsedTicker = () -> tickerCalls.getAndIncrement() == 0
                ? 0L
                : Duration.ofSeconds(2).toNanos();

        assertThatThrownBy(() -> client(
                baseUrl,
                Duration.ofSeconds(1),
                mapper,
                elapsedTicker
        ).analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_INVALID_RESPONSE
                            );
                            assertThat(exception.httpStatus()).hasValue(200);
                        }
                );

        assertThat(requestCount).hasValue(1);
    }

    @Test
    void removesOriginalBodyTransportCauseFromTheExternalException()
            throws Exception {
        String marker = "body_transport_exception_marker_137";
        String rawResponse = mapper.writeValueAsString(
                RuleAnalysisClientTestFixtures.matchedResponse(mapper)
        );
        response.set(jsonResponse(200, rawResponse));
        ObjectMapper transportFailureMapper = new ObjectMapper() {
            @Override
            public <T> T readValue(InputStream source, Class<T> valueType)
                    throws IOException {
                throw JsonMappingException.from(
                        (JsonParser) null,
                        "safe Jackson wrapper",
                        new IOException(marker)
                );
            }
        };

        assertThatThrownBy(() -> client(
                baseUrl,
                Duration.ofSeconds(1),
                transportFailureMapper,
                () -> 0L
        ).analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_UNAVAILABLE
                            );
                            assertThat(exception.httpStatus()).isEmpty();
                            assertSafeException(
                                    exception,
                                    marker,
                                    baseUrl.toString(),
                                    rawResponse
                            );
                        }
                );
    }

    @Test
    void mapsConnectionRefusalAsUnavailableWithoutRetry() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        RuleAnalysisHttpClient unavailableClient = client(
                URI.create("http://127.0.0.1:" + unusedPort),
                Duration.ofMillis(200)
        );

        assertThatThrownBy(() -> unavailableClient.analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> assertThat(exception.category()).isEqualTo(
                                RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE
                        )
                );
    }

    @Test
    void removesOriginalTransportCauseFromTheExternalException() {
        String marker = "transport_exception_marker_136";
        String originalMessage = "transport failure at " + baseUrl + " " + marker;
        ClientHttpRequestFactory failingRequestFactory = (uri, method) -> {
            throw new ResourceAccessException(
                    originalMessage,
                    new HttpConnectTimeoutException(marker)
            );
        };
        RestClient failingRestClient = RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(failingRequestFactory)
                .build();
        RuleAnalysisHttpClient failingClient = new RuleAnalysisHttpClient(
                failingRestClient,
                mapper,
                new RuleAnalysisResponseValidator(),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> failingClient.analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_CONNECT_TIMEOUT
                            );
                            assertSafeException(
                                    exception,
                                    marker,
                                    baseUrl.toString(),
                                    originalMessage
                            );
                        }
                );
    }

    @Test
    void removesOriginalJacksonCauseAndResponseValuesFromTheExternalException()
            throws Exception {
        String marker = "jackson_uuid_marker_136";
        ObjectNode invalidUuid = validResponseTree();
        invalidUuid.put("transactionId", marker);
        String rawResponse = mapper.writeValueAsString(invalidUuid);
        response.set(jsonResponse(200, rawResponse));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID))
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    RuleAnalysisClientErrorCategory
                                            .AI_SERVICE_INVALID_RESPONSE
                            );
                            assertSafeException(exception, marker, rawResponse);
                        }
                );
    }

    @Test
    void productionConfigurationPassesDefaultAndOverrideResponseTimeoutToClient() {
        AiServiceProperties defaults = new AiServiceProperties(
                baseUrl,
                null,
                null
        );
        AiServiceProperties override = new AiServiceProperties(
                baseUrl,
                Duration.ofMillis(200),
                Duration.ofMillis(750)
        );

        assertThat(productionClient(defaults).responseTimeout())
                .isEqualTo(AiServiceProperties.DEFAULT_RESPONSE_TIMEOUT);
        assertThat(productionClient(override).responseTimeout())
                .isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void rejectsInvalidTraceBeforePerformingAnHttpRequest() {
        assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                .analyze(request, " invalid trace "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("traceId must satisfy the internal trace contract");

        assertThat(requestCount).hasValue(0);
    }

    @Test
    void logsOnlyTheApprovedOperationalFields() {
        response.set(jsonResponse(
                500,
                errorJson("INTERNAL_ERROR", TRACE_ID)
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(
                RuleAnalysisHttpClient.class
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> client(Duration.ofSeconds(1))
                    .analyze(request, TRACE_ID))
                    .isInstanceOf(RuleAnalysisClientException.class);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .contains(
                                TRACE_ID,
                                "targetService=ai-service",
                                "endpoint=/api/v1/rule-analysis",
                                "category=AI_SERVICE_INTERNAL_ERROR",
                                "durationMs="
                        )
                        .doesNotContain(
                                "upstream sensitive message",
                                "customer_sensitive_ref",
                                "sender_sensitive_ref",
                                "recipient_sensitive_ref",
                                "device_sensitive_ref",
                                "conditionDefinition",
                                "observationSummary"
                        ));
    }

    private void assertInvalidTree(ObjectNode tree) throws Exception {
        response.set(jsonResponse(200, mapper.writeValueAsString(tree)));
        assertInvalidResponse(() -> client(Duration.ofSeconds(1))
                .analyze(request, TRACE_ID));
    }

    private void assertInvalidResponse(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RuleAnalysisClientException.class,
                        exception -> assertThat(exception.category()).isEqualTo(
                                RuleAnalysisClientErrorCategory
                                        .AI_SERVICE_INVALID_RESPONSE
                        )
                );
    }

    private ObjectNode validResponseTree() throws Exception {
        return (ObjectNode) mapper.valueToTree(
                RuleAnalysisClientTestFixtures.matchedResponse(mapper)
        );
    }

    private ArrayNode contributions(ObjectNode root) {
        return (ArrayNode) root.path("analysis")
                .path("scoringResult")
                .path("ruleContributions");
    }

    private ArrayNode groupSummaries(ObjectNode root) {
        return (ArrayNode) root.path("analysis")
                .path("scoringResult")
                .path("groupSummaries");
    }

    private ArrayNode evidence(ObjectNode root) {
        return (ArrayNode) root.path("analysis").path("evidence");
    }

    private RuleAnalysisHttpClient client(Duration responseTimeout) {
        return client(baseUrl, responseTimeout);
    }

    private RuleAnalysisHttpClient client(URI url, Duration responseTimeout) {
        RuleAnalysisClientConfiguration configuration =
                new RuleAnalysisClientConfiguration();
        AiServiceProperties properties = new AiServiceProperties(
                url,
                Duration.ofMillis(200),
                responseTimeout
        );
        return productionClient(configuration, properties);
    }

    private RuleAnalysisHttpClient productionClient(
            AiServiceProperties properties
    ) {
        return productionClient(new RuleAnalysisClientConfiguration(), properties);
    }

    private RuleAnalysisHttpClient productionClient(
            RuleAnalysisClientConfiguration configuration,
            AiServiceProperties properties
    ) {
        RestClient restClient = configuration.ruleAnalysisRestClient(
                properties,
                configuration.ruleAnalysisJdkHttpClient(properties),
                mapper
        );
        return configuration.ruleAnalysisHttpClient(
                restClient,
                mapper,
                new RuleAnalysisResponseValidator(),
                properties
        );
    }

    private RuleAnalysisHttpClient client(
            URI url,
            Duration responseTimeout,
            ObjectMapper responseMapper,
            LongSupplier ticker
    ) {
        RuleAnalysisClientConfiguration configuration =
                new RuleAnalysisClientConfiguration();
        AiServiceProperties properties = new AiServiceProperties(
                url,
                Duration.ofMillis(200),
                responseTimeout
        );
        return new RuleAnalysisHttpClient(
                configuration.ruleAnalysisRestClient(
                        properties,
                        configuration.ruleAnalysisJdkHttpClient(properties),
                        mapper
                ),
                responseMapper,
                new RuleAnalysisResponseValidator(),
                responseTimeout,
                ticker
        );
    }

    private void assertSafeException(
            RuleAnalysisClientException exception,
            String... forbiddenValues
    ) {
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getSuppressed()).isEmpty();
        assertThat(exception.getMessage()).isEqualTo(
                "AI Service Rule analysis call failed: " + exception.category()
        );
        assertThat(exception.getMessage()).doesNotContain(forbiddenValues);

        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        assertThat(stackTrace.toString()).doesNotContain(forbiddenValues);
    }

    private ResponseSpec jsonResponse(int status, String body) {
        return new ResponseSpec(
                status,
                "application/json; charset=UTF-8",
                List.of(TRACE_ID),
                body,
                Duration.ZERO
        );
    }

    private ResponseSpec partialJsonResponse(
            String body,
            Duration bodyDelay
    ) {
        int bodyLength = body.getBytes(StandardCharsets.UTF_8).length;
        return new ResponseSpec(
                200,
                "application/json; charset=UTF-8",
                List.of(TRACE_ID),
                body,
                Duration.ZERO,
                Math.max(1, bodyLength / 2),
                bodyDelay
        );
    }

    private String errorJson(String code, String traceId) {
        return """
                {
                  "code": "%s",
                  "message": "upstream sensitive message",
                  "traceId": "%s",
                  "fieldErrors": []
                }
                """.formatted(code, traceId);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestMethod.set(exchange.getRequestMethod());
        requestPath.set(exchange.getRequestURI().getPath());
        requestTraceHeaders.set(exchange.getRequestHeaders().get("X-Trace-Id"));
        requestContentTypes.set(exchange.getRequestHeaders().get("Content-Type"));
        requestAccepts.set(exchange.getRequestHeaders().get("Accept"));
        requestBody.set(exchange.getRequestBody().readAllBytes());
        ResponseSpec spec = response.get();
        delay(spec.headerDelay());
        if (spec.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", spec.contentType());
        }
        for (String trace : spec.traceHeaders()) {
            exchange.getResponseHeaders().add("X-Trace-Id", trace);
        }
        byte[] body = spec.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(spec.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                if (spec.initialBodyBytes() < 0) {
                    output.write(body);
                } else {
                    int prefixLength = Math.min(
                            spec.initialBodyBytes(),
                            body.length
                    );
                    output.write(body, 0, prefixLength);
                    output.flush();
                    responseBodyPrefixFlushed.set(true);
                    delay(spec.bodyDelay());
                    output.write(
                            body,
                            prefixLength,
                            body.length - prefixLength
                    );
                }
            }
        } catch (IOException exception) {
            // The client can close the response stream after its timeout expires.
        } finally {
            exchange.close();
        }
    }

    private void delay(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void readRequestHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            headers.write(current);
            byte[] bytes = headers.toByteArray();
            int length = bytes.length;
            if (length >= 4
                    && bytes[length - 4] == '\r'
                    && bytes[length - 3] == '\n'
                    && bytes[length - 2] == '\r'
                    && bytes[length - 1] == '\n') {
                return;
            }
        }
        throw new IOException("request headers ended prematurely");
    }

    private record ResponseSpec(
            int status,
            String contentType,
            List<String> traceHeaders,
            String body,
            Duration headerDelay,
            int initialBodyBytes,
            Duration bodyDelay
    ) {
        private ResponseSpec(
                int status,
                String contentType,
                List<String> traceHeaders,
                String body,
                Duration headerDelay
        ) {
            this(
                    status,
                    contentType,
                    traceHeaders,
                    body,
                    headerDelay,
                    -1,
                    Duration.ZERO
            );
        }
    }
}
