package com.aifds.backend.rule.client;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.rule.client.dto.RuleAnalysisErrorResponse;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class RuleAnalysisHttpClient {

    public static final String ENDPOINT = "/api/v1/rule-analysis";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RuleAnalysisHttpClient.class);
    private static final String TARGET_SERVICE = "ai-service";
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RuleAnalysisResponseValidator validator;
    private final Duration responseTimeout;
    private final long responseTimeoutNanos;
    private final LongSupplier ticker;

    public RuleAnalysisHttpClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            RuleAnalysisResponseValidator validator,
            Duration responseTimeout
    ) {
        this(
                restClient,
                objectMapper,
                validator,
                responseTimeout,
                System::nanoTime
        );
    }

    RuleAnalysisHttpClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            RuleAnalysisResponseValidator validator,
            Duration responseTimeout,
            LongSupplier ticker
    ) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.responseTimeout = requirePositiveTimeout(responseTimeout);
        this.responseTimeoutNanos = toNanosSaturated(responseTimeout);
        this.ticker = Objects.requireNonNull(ticker, "ticker must not be null");
    }

    public RuleAnalysisResponse analyze(
            RuleAnalysisRequest request,
            String traceId
    ) {
        Objects.requireNonNull(request, "request must not be null");
        requireValidTraceId(traceId);
        long startedAt = ticker.getAsLong();
        try {
            RuleAnalysisResponse response = restClient.post()
                    .uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                    .body(request)
                    .exchange((httpRequest, httpResponse) -> handleResponse(
                            request,
                            traceId,
                            httpResponse,
                            startedAt
                    ));
            logSuccess(traceId, elapsedMillis(startedAt));
            return response;
        } catch (RuleAnalysisClientException exception) {
            logFailure(traceId, exception, elapsedMillis(startedAt));
            throw exception;
        } catch (ResourceAccessException exception) {
            RuleAnalysisClientException classified = new RuleAnalysisClientException(
                    RuleAnalysisTransportErrorClassifier.classify(exception),
                    null
            );
            logFailure(traceId, classified, elapsedMillis(startedAt));
            throw classified;
        } catch (RestClientException exception) {
            RuleAnalysisClientException invalid = invalidResponse(null);
            logFailure(traceId, invalid, elapsedMillis(startedAt));
            throw invalid;
        }
    }

    private RuleAnalysisResponse handleResponse(
            RuleAnalysisRequest request,
            String requestTraceId,
            ClientHttpResponse response,
            long startedAt
    ) {
        int status;
        try {
            status = response.getStatusCode().value();
        } catch (IOException exception) {
            throw invalidResponse(null);
        }
        requireJsonContentType(response.getHeaders(), status);
        requireSingleMatchingTrace(
                requestTraceId,
                response.getHeaders(),
                status
        );

        if (status == 200) {
            RuleAnalysisResponse success = readBody(
                    response,
                    RuleAnalysisResponse.class,
                    status,
                    startedAt
            );
            requireMatchingBodyTrace(requestTraceId, success.traceId(), status);
            try {
                validator.validate(request, success);
            } catch (RuleAnalysisClientException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw invalidResponse(status);
            }
            return success;
        }

        if (!isSupportedErrorStatus(status)) {
            throw invalidResponse(status);
        }
        RuleAnalysisErrorResponse error = readBody(
                response,
                RuleAnalysisErrorResponse.class,
                status,
                startedAt
        );
        requireMatchingBodyTrace(requestTraceId, error.traceId(), status);
        throw new RuleAnalysisClientException(
                mapError(status, error.code()),
                status
        );
    }

    private <T> T readBody(
            ClientHttpResponse response,
            Class<T> bodyType,
            int status,
            long startedAt
    ) {
        try {
            T body = objectMapper.readValue(response.getBody(), bodyType);
            if (body == null) {
                throw invalidResponse(status);
            }
            return body;
        } catch (RuleAnalysisClientException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            var transportCategory = RuleAnalysisTransportErrorClassifier
                    .classifyBodyReadFailure(
                            exception,
                            () -> responseTimeoutExpired(startedAt)
                    );
            if (transportCategory.isPresent()) {
                throw new RuleAnalysisClientException(
                        transportCategory.get(),
                        null
                );
            }
            throw invalidResponse(status);
        }
    }

    Duration responseTimeout() {
        return responseTimeout;
    }

    private void requireJsonContentType(HttpHeaders headers, int status) {
        MediaType contentType;
        try {
            contentType = headers.getContentType();
        } catch (RuntimeException exception) {
            throw invalidResponse(status);
        }
        if (contentType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            throw invalidResponse(status);
        }
    }

    private void requireSingleMatchingTrace(
            String requestTraceId,
            HttpHeaders headers,
            int status
    ) {
        List<String> values = headers.get(TraceIdFilter.TRACE_ID_HEADER);
        if (values == null
                || values.size() != 1
                || !requestTraceId.equals(values.get(0))) {
            throw invalidResponse(status);
        }
    }

    private void requireMatchingBodyTrace(
            String requestTraceId,
            String bodyTraceId,
            int status
    ) {
        if (!requestTraceId.equals(bodyTraceId)) {
            throw invalidResponse(status);
        }
    }

    private RuleAnalysisClientErrorCategory mapError(int status, String code) {
        if (status == 400 && "INVALID_REQUEST".equals(code)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_REQUEST_CONTRACT_ERROR;
        }
        if (status == 413 && "PAYLOAD_TOO_LARGE".equals(code)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_PAYLOAD_TOO_LARGE;
        }
        if (status == 422 && "RULE_CONTRACT_ERROR".equals(code)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_RULE_CONTRACT_ERROR;
        }
        if (status == 500 && "UNSUPPORTED_RULE_CAPABILITY".equals(code)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_CAPABILITY_MISMATCH;
        }
        if (status == 500 && "INTERNAL_ERROR".equals(code)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_INTERNAL_ERROR;
        }
        throw invalidResponse(status);
    }

    private boolean isSupportedErrorStatus(int status) {
        return status == 400 || status == 413 || status == 422 || status == 500;
    }

    private void requireValidTraceId(String traceId) {
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId must satisfy the internal trace contract");
        }
    }

    private RuleAnalysisClientException invalidResponse(Integer status) {
        return new RuleAnalysisClientException(
                RuleAnalysisClientErrorCategory.AI_SERVICE_INVALID_RESPONSE,
                status
        );
    }

    private void logSuccess(String traceId, long elapsedMillis) {
        LOGGER.info(
                "Rule analysis call completed traceId={} targetService={} endpoint={} "
                        + "httpStatusClass=2xx durationMs={}",
                traceId,
                TARGET_SERVICE,
                ENDPOINT,
                elapsedMillis
        );
    }

    private void logFailure(
            String traceId,
            RuleAnalysisClientException exception,
            long elapsedMillis
    ) {
        LOGGER.warn(
                "Rule analysis call failed traceId={} targetService={} endpoint={} "
                        + "httpStatusClass={} category={} durationMs={}",
                traceId,
                TARGET_SERVICE,
                ENDPOINT,
                httpStatusClass(exception),
                exception.category(),
                elapsedMillis
        );
    }

    private String httpStatusClass(RuleAnalysisClientException exception) {
        if (exception.httpStatus().isEmpty()) {
            return "transport";
        }
        return (exception.httpStatus().getAsInt() / 100) + "xx";
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, ticker.getAsLong() - startedAt) / 1_000_000;
    }

    private boolean responseTimeoutExpired(long startedAt) {
        return ticker.getAsLong() - startedAt >= responseTimeoutNanos;
    }

    private static Duration requirePositiveTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "responseTimeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        return timeout;
    }

    private static long toNanosSaturated(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
