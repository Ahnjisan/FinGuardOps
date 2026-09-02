package com.aifds.backend.security.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinGuardOpsSecurityPropertiesTest {

    @Test
    void acceptsHttpsEndpointsExactOriginsAndPositiveTimeouts() {
        FinGuardOpsSecurityProperties properties = properties(
                URI.create("https://issuer.example.test/finguardops"),
                URI.create("https://issuer.example.test/jwks"),
                List.of("https://console.example.test", "http://localhost:5173"),
                false
        );

        assertThat(properties.allowedOrigins()).containsExactly(
                "https://console.example.test",
                "http://localhost:5173"
        );
        assertThat(properties.jwkConnectTimeout()).isEqualTo(
                Duration.ofSeconds(2)
        );
    }

    @Test
    void normalizesMissingAllowedOriginsToEmptyList() {
        FinGuardOpsSecurityProperties properties = properties(
                URI.create("https://issuer.example.test/finguardops"),
                URI.create("https://issuer.example.test/jwks"),
                null,
                false
        );

        assertThat(properties.allowedOrigins()).isEmpty();
    }

    @Test
    void rejectsNonHttpsIssuerAndNonLoopbackHttpJwkUri() {
        assertThatThrownBy(() -> properties(
                URI.create("http://issuer.example.test/finguardops"),
                URI.create("https://issuer.example.test/jwks"),
                List.of(),
                false
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> properties(
                URI.create("https://issuer.example.test/finguardops"),
                URI.create("http://issuer.example.test/jwks"),
                List.of(),
                true
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsExplicitTestOnlyLoopbackJwkUri() {
        FinGuardOpsSecurityProperties properties = properties(
                URI.create("https://issuer.example.test/finguardops"),
                URI.create("http://127.0.0.1:18080/jwks"),
                List.of(),
                true
        );

        assertThat(properties.jwkSetUri().getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsWildcardMalformedAndDetailedCorsOrigins() {
        List<String> invalidOrigins = List.of(
                "*",
                "https://*.example.test",
                "not-an-origin",
                "https://console.example.test/path",
                "https://user@console.example.test",
                "https://console.example.test?credential=secret"
        );

        invalidOrigins.forEach(origin -> assertThatThrownBy(() -> properties(
                URI.create("https://issuer.example.test/finguardops"),
                URI.create("https://issuer.example.test/jwks"),
                List.of(origin),
                false
        )).isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void copiesAllowedOriginsAndRejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new FinGuardOpsSecurityProperties(
                URI.create("https://issuer.example.test/finguardops"),
                URI.create("https://issuer.example.test/jwks"),
                List.of(),
                Duration.ZERO,
                Duration.ofSeconds(2),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private FinGuardOpsSecurityProperties properties(
            URI issuer,
            URI jwkSetUri,
            List<String> origins,
            boolean insecureLoopbackJwkAllowed
    ) {
        return new FinGuardOpsSecurityProperties(
                issuer,
                jwkSetUri,
                origins,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                insecureLoopbackJwkAllowed
        );
    }
}
