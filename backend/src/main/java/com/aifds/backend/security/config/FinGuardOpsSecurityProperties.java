package com.aifds.backend.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties("finguardops.security")
public record FinGuardOpsSecurityProperties(
        @DefaultValue("https://issuer.example.invalid/finguardops") URI issuer,
        @DefaultValue("https://issuer.example.invalid/.well-known/jwks.json")
        URI jwkSetUri,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("2s") Duration jwkConnectTimeout,
        @DefaultValue("2s") Duration jwkReadTimeout,
        @DefaultValue("false") boolean insecureLoopbackJwkAllowed
) {

    public static final String AUDIENCE = "finguardops-backend-api";
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    public static final Duration MAX_TOKEN_LIFETIME = Duration.ofMinutes(15);

    public FinGuardOpsSecurityProperties {
        requireHttpsIssuer(issuer);
        requireApprovedJwkSetUri(jwkSetUri, insecureLoopbackJwkAllowed);
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : List.copyOf(allowedOrigins);
        allowedOrigins.forEach(FinGuardOpsSecurityProperties::validateOrigin);
        requirePositiveTimeout(jwkConnectTimeout, "jwkConnectTimeout");
        requirePositiveTimeout(jwkReadTimeout, "jwkReadTimeout");
    }

    private static void requireHttpsIssuer(URI value) {
        requireAbsoluteHttpUri(value, "issuer");
        if (!"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("issuer must use HTTPS");
        }
    }

    private static void requireApprovedJwkSetUri(
            URI value,
            boolean insecureLoopbackJwkAllowed
    ) {
        requireAbsoluteHttpUri(value, "jwkSetUri");
        if ("https".equalsIgnoreCase(value.getScheme())) {
            return;
        }
        if (!insecureLoopbackJwkAllowed
                || !"http".equalsIgnoreCase(value.getScheme())
                || !isLoopbackHost(value.getHost())) {
            throw new IllegalArgumentException("jwkSetUri must use HTTPS");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private static void validateOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) {
            throw new IllegalArgumentException(
                    "allowedOrigins must contain exact origins"
            );
        }
        URI value;
        try {
            value = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "allowedOrigins must contain valid origins"
            );
        }
        requireAbsoluteHttpUri(value, "allowedOrigins");
        if (value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || (value.getPath() != null && !value.getPath().isEmpty())) {
            throw new IllegalArgumentException(
                    "allowedOrigins must not contain URL details"
            );
        }
    }

    private static void requireAbsoluteHttpUri(URI value, String name) {
        if (value == null
                || !value.isAbsolute()
                || value.getHost() == null
                || !("https".equalsIgnoreCase(value.getScheme())
                || "http".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    name + " must be an absolute HTTP(S) URI"
            );
        }
    }

    private static void requirePositiveTimeout(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
