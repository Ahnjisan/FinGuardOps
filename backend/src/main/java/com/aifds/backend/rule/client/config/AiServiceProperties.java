package com.aifds.backend.rule.client.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "finguardops.ai-service")
public record AiServiceProperties(
        @NotNull URI baseUrl,
        Duration connectTimeout,
        Duration responseTimeout
) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    public AiServiceProperties {
        connectTimeout = connectTimeout == null
                ? DEFAULT_CONNECT_TIMEOUT
                : requirePositive(connectTimeout, "connectTimeout");
        responseTimeout = responseTimeout == null
                ? DEFAULT_RESPONSE_TIMEOUT
                : requirePositive(responseTimeout, "responseTimeout");
        if (baseUrl != null) {
            requireHttpBaseUrl(baseUrl);
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static void requireHttpBaseUrl(URI value) {
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URI"
            );
        }
        if (value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must not contain credentials, query, or fragment"
            );
        }
        String rawPath = value.getRawPath();
        if (!(rawPath.isEmpty() || "/".equals(rawPath))) {
            throw new IllegalArgumentException(
                    "baseUrl must contain only an origin without a path"
            );
        }
    }
}
