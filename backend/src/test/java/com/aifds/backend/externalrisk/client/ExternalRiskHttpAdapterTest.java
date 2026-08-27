package com.aifds.backend.externalrisk.client;

import com.aifds.backend.externalrisk.client.config.ExternalRiskHttpConfiguration;
import com.aifds.backend.externalrisk.client.config.ExternalRiskHttpProperties;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ExternalRiskHttpAdapterTest {

    private static final String TRACE_ID = "trace-external-risk-http-0001";
    private static final String API_KEY = "unit-test-secret-api-key";
    private static final String CUSTOMER_REF = "sensitive-customer-reference";
    private static final String SENDER_REF = "sensitive-sender-reference";

    private HttpServer server;
    private ExecutorService executor;
    private URI baseUrl;
    private final AtomicReference<ResponseSpec> response = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<List<String>> authorization =
            new AtomicReference<>();
    private final AtomicReference<List<String>> traceHeaders =
            new AtomicReference<>();
    private final AtomicReference<List<String>> contentTypes =
            new AtomicReference<>();
    private final AtomicReference<List<String>> accepts = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        response.set(json(200, validResponse()));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void sendsTheExactRequestOnceWithAuthenticationAndTrace() throws Exception {
        var actual = client(baseUrl, Duration.ofSeconds(1), 65_536)
                .lookup(request());

        assertThat(actual.providerCode()).isEqualTo("PROVIDER_V1");
        assertThat(actual.matches()).hasSize(1);
        assertThat(requestCount).hasValue(1);
        assertThat(requestMethod).hasValue("POST");
        assertThat(requestPath)
                .hasValue(ExternalRiskHttpAdapter.ENDPOINT);
        assertThat(authorization.get()).containsExactly("Bearer " + API_KEY);
        assertThat(traceHeaders.get()).containsExactly(TRACE_ID);
        assertThat(contentTypes.get()).singleElement()
                .satisfies(value -> assertThat(value)
                        .startsWith("application/json"));
        assertThat(accepts.get()).containsExactly("application/json");

        JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(requestBody.get());
        assertThat(json.size()).isEqualTo(7);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "transactionType",
                "evaluationCutoffAt",
                "externalCustomerRef",
                "senderAccountRef",
                "recipientAccountRef",
                "deviceRef",
                "traceId"
        );
        assertThat(json.path("transactionType").asText())
                .isEqualTo("ACCOUNT_TRANSFER");
        assertThat(json.path("evaluationCutoffAt").asText())
                .isEqualTo("2026-08-27T01:02:03.123456Z");
        assertThat(json.get("recipientAccountRef").isNull()).isTrue();
        assertThat(json.get("deviceRef").isNull()).isTrue();
        assertThat(json.has("transactionId")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("statusMappings")
    void mapsEveryHttpStatusWithoutRetry(
            int status,
            ExternalRiskFailureCategory category
    ) {
        response.set(json(status, "{}"));

        assertFailure(category, () -> client().lookup(request()));
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void mapsNonSuccessStatusWithoutReadingOrClassifyingItsRawBody() {
        response.set(json(400, validResponse().repeat(10)));

        assertFailure(
                ExternalRiskFailureCategory.INVALID_REQUEST,
                () -> client(baseUrl, Duration.ofSeconds(1), 64)
                        .lookup(request())
        );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void neverFollowsRedirects() {
        response.set(new ResponseSpec(
                302,
                "application/json",
                "{}".getBytes(StandardCharsets.UTF_8),
                false,
                Duration.ZERO,
                baseUrl.resolve("/redirect-target").toString()
        ));

        assertFailure(
                ExternalRiskFailureCategory.UNAVAILABLE,
                () -> client().lookup(request())
        );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void mapsReadTimeoutAndConnectionFailureWithoutRetry() throws Exception {
        response.set(new ResponseSpec(
                200,
                "application/json",
                validResponse().getBytes(StandardCharsets.UTF_8),
                false,
                Duration.ofMillis(250),
                null
        ));
        assertFailure(
                ExternalRiskFailureCategory.TIMEOUT,
                () -> client(baseUrl, Duration.ofMillis(50), 65_536)
                        .lookup(request())
        );
        assertThat(requestCount).hasValue(1);

        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        URI unavailable = URI.create("http://127.0.0.1:" + unusedPort);
        assertFailure(
                ExternalRiskFailureCategory.UNAVAILABLE,
                () -> client(unavailable, Duration.ofMillis(100), 65_536)
                        .lookup(request())
        );
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsMalformedAndStrictContractViolations(String body) {
        response.set(json(200, body));

        assertFailure(
                ExternalRiskFailureCategory.INVALID_RESPONSE,
                () -> client().lookup(request())
        );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void rejectsEmptyAndNonJsonResponses() {
        response.set(json(200, ""));
        assertFailure(
                ExternalRiskFailureCategory.INVALID_RESPONSE,
                () -> client().lookup(request())
        );
        requestCount.set(0);
        response.set(new ResponseSpec(
                200,
                "application/json; charset=UTF-8; boundary=forbidden",
                validResponse().getBytes(StandardCharsets.UTF_8),
                false,
                Duration.ZERO,
                null
        ));
        assertFailure(
                ExternalRiskFailureCategory.INVALID_RESPONSE,
                () -> client().lookup(request())
        );
        assertThat(requestCount).hasValue(1);
        requestCount.set(0);
        response.set(new ResponseSpec(
                200,
                "text/plain",
                validResponse().getBytes(StandardCharsets.UTF_8),
                false,
                Duration.ZERO,
                null
        ));
        assertFailure(
                ExternalRiskFailureCategory.INVALID_RESPONSE,
                () -> client().lookup(request())
        );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void rejectsDeclaredAndChunkedBodiesBeyondTheConfiguredLimit() {
        byte[] oversized = validResponse().repeat(10)
                .getBytes(StandardCharsets.UTF_8);
        response.set(new ResponseSpec(
                200,
                "application/json",
                oversized,
                false,
                Duration.ZERO,
                null
        ));
        assertFailure(
                ExternalRiskFailureCategory.INVALID_RESPONSE,
                () -> client(baseUrl, Duration.ofSeconds(1), 64)
                        .lookup(request())
        );

        requestCount.set(0);
        response.set(new ResponseSpec(
                200,
                "application/json; charset=UTF-8",
                oversized,
                true,
                Duration.ZERO,
                null
        ));
        assertFailure(
                ExternalRiskFailureCategory.INVALID_RESPONSE,
                () -> client(baseUrl, Duration.ofSeconds(1), 64)
                        .lookup(request())
        );
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void failsBeforeCallingWhenATransactionIsActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Throwable failure = catchThrowable(() -> client().lookup(request()));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void exposesOnlyTheFixedTypedMessage() {
        String rawBody = "raw-provider-body-should-never-escape";
        response.set(json(200, rawBody));

        ExternalRiskLookupException failure = catchThrowableOfType(
                ExternalRiskLookupException.class,
                () -> client().lookup(request())
        );

        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).doesNotContain(
                API_KEY,
                CUSTOMER_REF,
                SENDER_REF,
                rawBody
        );
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString()).doesNotContain(
                API_KEY,
                CUSTOMER_REF,
                SENDER_REF,
                rawBody
        );
    }

    private static Stream<Arguments> statusMappings() {
        return Stream.of(
                Arguments.of(201, ExternalRiskFailureCategory.INVALID_RESPONSE),
                Arguments.of(204, ExternalRiskFailureCategory.INVALID_RESPONSE),
                Arguments.of(300, ExternalRiskFailureCategory.UNAVAILABLE),
                Arguments.of(400, ExternalRiskFailureCategory.INVALID_REQUEST),
                Arguments.of(401, ExternalRiskFailureCategory.UNAVAILABLE),
                Arguments.of(408, ExternalRiskFailureCategory.TIMEOUT),
                Arguments.of(422, ExternalRiskFailureCategory.INVALID_REQUEST),
                Arguments.of(429, ExternalRiskFailureCategory.UNAVAILABLE),
                Arguments.of(500, ExternalRiskFailureCategory.UNAVAILABLE),
                Arguments.of(501, ExternalRiskFailureCategory.UNSUPPORTED_CAPABILITY),
                Arguments.of(503, ExternalRiskFailureCategory.UNAVAILABLE),
                Arguments.of(504, ExternalRiskFailureCategory.TIMEOUT)
        );
    }

    private static Stream<String> invalidPayloads() {
        return Stream.of(
                "{",
                "{}",
                "{\"providerCode\":\"PROVIDER_V1\",\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[],\"extra\":1}",
                "{\"providerCode\":null,\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[]}",
                "{\"providerCode\":1,\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[]}",
                "{\"providerCode\":\"bad-code\",\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[]}",
                "{\"providerCode\":\"PROVIDER_V1\",\"providerCode\":"
                        + "\"PROVIDER_V2\",\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[]}",
                "{\"providerCode\":\"PROVIDER_V1\",\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03.1234567Z\",\"matches\":[]}",
                "{\"providerCode\":\"PROVIDER_V1\",\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[{"
                        + "\"subjectType\":\"UNKNOWN\",\"riskType\":"
                        + "\"RISK_DEVICE\",\"reasonCode\":\"RISK_DEVICE\"}]}",
                "{\"providerCode\":\"PROVIDER_V1\",\"providerAsOf\":"
                        + "\"2026-08-27T01:02:03Z\",\"matches\":[{"
                        + "\"subjectType\":\"DEVICE\",\"riskType\":"
                        + "\"SUSPICIOUS_ACCOUNT\",\"reasonCode\":"
                        + "\"RISK_DEVICE\"}]}",
                validResponse() + " {}"
        );
    }

    private ExternalRiskHttpAdapter client() {
        return client(baseUrl, Duration.ofSeconds(1), 65_536);
    }

    private ExternalRiskHttpAdapter client(
            URI url,
            Duration readTimeout,
            int maxResponseBytes
    ) {
        ExternalRiskHttpProperties properties = new ExternalRiskHttpProperties(
                true,
                url,
                API_KEY,
                Duration.ofMillis(100),
                readTimeout,
                maxResponseBytes
        );
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", ExternalRiskHttpConfiguration.HTTP_PROFILE);
        ExternalRiskHttpConfiguration configuration =
                new ExternalRiskHttpConfiguration(environment, properties);
        var jsonCodec = configuration.externalRiskObjectMapper(
                Jackson2ObjectMapperBuilder.json()
        );
        HttpClient httpClient = configuration.externalRiskJdkHttpClient(properties);
        RestClient restClient = configuration.externalRiskRestClient(
                properties,
                httpClient,
                jsonCodec
        );
        return configuration.externalRiskHttpAdapter(
                restClient,
                jsonCodec,
                new ExternalRiskHttpMapper(),
                properties
        );
    }

    private ExternalRiskProviderRequest request() {
        return new ExternalRiskProviderRequest(
                TransactionType.ACCOUNT_TRANSFER,
                Instant.parse("2026-08-27T01:02:03.123456Z"),
                CUSTOMER_REF,
                SENDER_REF,
                null,
                null,
                TRACE_ID
        );
    }

    private void assertFailure(
            ExternalRiskFailureCategory category,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        ExternalRiskLookupException failure = catchThrowableOfType(
                ExternalRiskLookupException.class,
                call
        );
        assertThat(failure.category()).isEqualTo(category);
        assertThat(failure.getCause()).isNull();
    }

    private Set<String> fieldNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private ResponseSpec json(int status, String body) {
        return new ResponseSpec(
                status,
                "application/json; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8),
                false,
                Duration.ZERO,
                null
        );
    }

    private static String validResponse() {
        return """
                {
                  "providerCode": "PROVIDER_V1",
                  "providerAsOf": "2026-08-27T01:02:03.123456Z",
                  "matches": [
                    {
                      "subjectType": "SENDER_ACCOUNT",
                      "riskType": "SUSPICIOUS_ACCOUNT",
                      "reasonCode": "SUSPICIOUS_SENDER_ACCOUNT"
                    }
                  ]
                }
                """;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestMethod.set(exchange.getRequestMethod());
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().get("Authorization"));
        traceHeaders.set(exchange.getRequestHeaders().get("X-Trace-Id"));
        contentTypes.set(exchange.getRequestHeaders().get("Content-Type"));
        accepts.set(exchange.getRequestHeaders().get("Accept"));
        requestBody.set(exchange.getRequestBody().readAllBytes());
        ResponseSpec spec = response.get();
        delay(spec.delay());
        if (spec.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", spec.contentType());
        }
        if (spec.location() != null) {
            exchange.getResponseHeaders().set("Location", spec.location());
        }
        long responseLength = spec.chunked() ? 0 : spec.body().length;
        try {
            exchange.sendResponseHeaders(spec.status(), responseLength);
            exchange.getResponseBody().write(spec.body());
        } catch (IOException ignored) {
            // A timeout or bounded reader may close the client side first.
        } finally {
            exchange.close();
        }
    }

    private void delay(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record ResponseSpec(
            int status,
            String contentType,
            byte[] body,
            boolean chunked,
            Duration delay,
            String location
    ) {
    }
}
