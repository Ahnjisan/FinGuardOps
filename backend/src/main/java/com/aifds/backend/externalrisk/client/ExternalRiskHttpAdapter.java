package com.aifds.backend.externalrisk.client;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpRequest;
import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;
import com.aifds.backend.externalrisk.port.ExternalRiskLookupPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class ExternalRiskHttpAdapter implements ExternalRiskLookupPort {

    public static final String ENDPOINT = "/v1/external-risk/lookup";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExternalRiskHttpMapper mapper;
    private final int maxResponseBytes;

    public ExternalRiskHttpAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            ExternalRiskHttpMapper mapper,
            int maxResponseBytes
    ) {
        this.restClient = Objects.requireNonNull(
                restClient,
                "restClient must not be null"
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        if (maxResponseBytes < 1 || maxResponseBytes > 65_536) {
            throw new IllegalArgumentException("maxResponseBytes is invalid");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public ExternalRiskProviderResponse lookup(
            ExternalRiskProviderRequest request
    ) {
        requireNoActiveTransaction();
        ExternalRiskHttpRequest httpRequest = mapper.toHttpRequest(request);
        validateRequestSerialization(httpRequest);
        try {
            return restClient.post()
                    .uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(TraceIdFilter.TRACE_ID_HEADER, request.traceId())
                    .body(httpRequest)
                    .exchange((ignored, response) -> handleResponse(response));
        } catch (ExternalRiskLookupException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw failure(ExternalRiskTransportErrorClassifier.classify(exception));
        } catch (RestClientException exception) {
            throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
        }
    }

    private void validateRequestSerialization(ExternalRiskHttpRequest request) {
        try {
            objectMapper.writeValueAsBytes(request);
        } catch (IOException | RuntimeException exception) {
            throw failure(ExternalRiskFailureCategory.INVALID_REQUEST);
        }
    }

    private ExternalRiskProviderResponse handleResponse(
            ClientHttpResponse response
    ) {
        int status;
        try {
            status = response.getStatusCode().value();
        } catch (IOException exception) {
            throw failure(ExternalRiskTransportErrorClassifier.classify(exception));
        }

        if (status != 200) {
            throw failure(categoryForStatus(status));
        }

        byte[] body;
        try {
            body = readBounded(response);
        } catch (ExternalRiskLookupException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(ExternalRiskTransportErrorClassifier.classify(exception));
        }
        if (!hasJsonContentType(response) || body.length == 0) {
            throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
        }
        try {
            ExternalRiskHttpResponse decoded = objectMapper.readValue(
                    body,
                    ExternalRiskHttpResponse.class
            );
            return mapper.toDomainResponse(decoded);
        } catch (ExternalRiskLookupException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
        }
    }

    private byte[] readBounded(ClientHttpResponse response) throws IOException {
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maxResponseBytes) {
            throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
        }
        try (InputStream stream = response.getBody()) {
            byte[] body = stream.readNBytes(maxResponseBytes + 1);
            if (body.length > maxResponseBytes) {
                throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
            }
            return body;
        }
    }

    private boolean hasJsonContentType(ClientHttpResponse response) {
        try {
            java.util.List<String> values = response.getHeaders().get(
                    HttpHeaders.CONTENT_TYPE
            );
            if (values == null || values.size() != 1) {
                return false;
            }
            MediaType contentType = response.getHeaders().getContentType();
            return contentType != null
                    && "application".equalsIgnoreCase(contentType.getType())
                    && "json".equalsIgnoreCase(contentType.getSubtype())
                    && contentType.getParameters().keySet().stream()
                    .allMatch("charset"::equalsIgnoreCase);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ExternalRiskFailureCategory categoryForStatus(int status) {
        if (status >= 200 && status < 300) {
            return ExternalRiskFailureCategory.INVALID_RESPONSE;
        }
        return switch (status) {
            case 400, 422 -> ExternalRiskFailureCategory.INVALID_REQUEST;
            case 408, 504 -> ExternalRiskFailureCategory.TIMEOUT;
            case 501 -> ExternalRiskFailureCategory.UNSUPPORTED_CAPABILITY;
            default -> ExternalRiskFailureCategory.UNAVAILABLE;
        };
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "External Risk HTTP call requires no active transaction"
            );
        }
    }

    private ExternalRiskLookupException failure(
            ExternalRiskFailureCategory category
    ) {
        return new ExternalRiskLookupException(category);
    }
}
