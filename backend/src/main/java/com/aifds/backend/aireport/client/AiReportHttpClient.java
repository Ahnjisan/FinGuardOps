package com.aifds.backend.aireport.client;

import com.aifds.backend.aireport.config.AiReportProperties;
import com.aifds.backend.aireport.dto.AiReportDtos;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

@Component
public class AiReportHttpClient {
    private final RestClient identityClient;
    private final RestClient generationClient;

    public AiReportHttpClient(AiReportProperties.Values properties) {
        SimpleClientHttpRequestFactory identityFactory = new SimpleClientHttpRequestFactory();
        identityFactory.setConnectTimeout(1000);
        identityFactory.setReadTimeout(2000);
        this.identityClient = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(identityFactory).build();
        SimpleClientHttpRequestFactory generationFactory = new SimpleClientHttpRequestFactory();
        generationFactory.setConnectTimeout(2000);
        generationFactory.setReadTimeout(240000);
        this.generationClient = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(generationFactory).build();
    }

    public AiReportDtos.ModelIdentity identity() {
        return identityClient.get().uri("/api/v1/ai-reports/model")
                .retrieve().body(AiReportDtos.ModelIdentity.class);
    }

    public AiReportDtos.GenerationResult generate(AiReportDtos.GenerationRequest request) {
        try {
            return generationClient.post().uri("/api/v1/ai-reports")
                    .body(request).retrieve().body(AiReportDtos.GenerationResult.class);
        } catch (ResourceAccessException exception) {
            throw new GenerationFailure(classifyAccessFailure(exception));
        } catch (RestClientResponseException exception) {
            throw new GenerationFailure(exception.getStatusCode().is5xxServerError()
                    ? "DEPENDENCY_UNAVAILABLE" : "FASTAPI_RESPONSE_INVALID");
        } catch (RestClientException exception) {
            throw new GenerationFailure("FASTAPI_RESPONSE_INVALID");
        }
    }

    static String classifyAccessFailure(ResourceAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
                    || cause instanceof UnknownHostException) {
                return "FASTAPI_CONNECTION_FAILED";
            }
            if (cause instanceof SocketTimeoutException timeout) {
                String message = timeout.getMessage();
                return message != null && message.toLowerCase(java.util.Locale.ROOT)
                        .contains("connect") ? "FASTAPI_CONNECTION_FAILED" : "FASTAPI_TIMEOUT";
            }
        }
        return "DEPENDENCY_UNAVAILABLE";
    }

    public static final class GenerationFailure extends RuntimeException {
        private final String code;

        public GenerationFailure(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }
}
