package com.aifds.backend.externalrisk.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = ExternalRiskHttpConfiguration.PROPERTY_PREFIX)
public record ExternalRiskHttpProperties(
        boolean enabled,
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Integer maxResponseBytes
) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(3);
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 65_536;

    public ExternalRiskHttpProperties {
        connectTimeout = connectTimeout == null
                ? DEFAULT_CONNECT_TIMEOUT
                : requirePositive(connectTimeout, "connectTimeout");
        readTimeout = readTimeout == null
                ? DEFAULT_READ_TIMEOUT
                : requirePositive(readTimeout, "readTimeout");
        maxResponseBytes = maxResponseBytes == null
                ? DEFAULT_MAX_RESPONSE_BYTES
                : requireBodyLimit(maxResponseBytes);
        if (baseUrl != null) {
            requireOriginOnlyHttpUri(baseUrl);
        }
        if (enabled) {
            if (baseUrl == null) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            if (apiKey.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("apiKey is invalid");
            }
        }
    }

    @Override
    public String toString() {
        return "ExternalRiskHttpProperties[enabled=" + enabled
                + ", baseUrlConfigured=" + (baseUrl != null)
                + ", apiKey=REDACTED, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int requireBodyLimit(int value) {
        if (value < 1 || value > DEFAULT_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be between 1 and 65536"
            );
        }
        return value;
    }

    private static void requireOriginOnlyHttpUri(URI value) {
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) origin"
            );
        }
        if (value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must not contain user-info, query, or fragment"
            );
        }
        String path = value.getRawPath();
        if (!(path.isEmpty() || "/".equals(path))) {
            throw new IllegalArgumentException("baseUrl must not contain a path");
        }
    }
}
