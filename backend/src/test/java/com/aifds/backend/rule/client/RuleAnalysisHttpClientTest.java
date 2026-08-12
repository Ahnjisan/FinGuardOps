package com.aifds.backend.rule.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifds.backend.rule.client.config.AiServiceProperties;
import com.aifds.backend.rule.client.config.RuleAnalysisClientConfiguration;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.aifds.backend.rule.client.RuleAnalysisClientTestFixtures.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleAnalysisHttpClientTest {

    private HttpServer server;
    private URI baseUrl;
    private ObjectMapper mapper;
    private RuleAnalysisRequest request;
    private final AtomicReference<ResponseSpec> response = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private final AtomicReference<List<String>> requestTraceHeaders =
            new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        RuleAnalysisClientConfiguration configuration =
                new RuleAnalysisClientConfiguration();
        mapper = configuration.ruleAnalysisObjectMapper(
                new Jackson2ObjectMapperBuilder()
        );
        request = RuleAnalysisClientTestFixtures.request(mapper);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RuleAnalysisHttpClient.ENDPOINT, this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
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
    void mapsActualResponseTimeoutAndNeverReturnsANormalResult() {
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
                new RuleAnalysisResponseValidator()
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
        return new RuleAnalysisHttpClient(
                configuration.ruleAnalysisRestClient(
                        properties,
                        configuration.ruleAnalysisJdkHttpClient(properties),
                        mapper
                ),
                mapper,
                new RuleAnalysisResponseValidator()
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
        requestBody.set(exchange.getRequestBody().readAllBytes());
        ResponseSpec spec = response.get();
        if (!spec.delay().isZero()) {
            try {
                Thread.sleep(spec.delay().toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (spec.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", spec.contentType());
        }
        for (String trace : spec.traceHeaders()) {
            exchange.getResponseHeaders().add("X-Trace-Id", trace);
        }
        byte[] body = spec.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(spec.status(), body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private record ResponseSpec(
            int status,
            String contentType,
            List<String> traceHeaders,
            String body,
            Duration delay
    ) {
    }
}
