package com.aifds.backend.security.jwt;

import com.aifds.backend.security.config.FinGuardOpsSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinGuardOpsJwtValidatorTest {

    private static final Instant VALIDATION_TIME =
            Instant.parse("2026-09-03T00:00:00Z");
    private static final FinGuardOpsJwtValidator VALIDATOR =
            new FinGuardOpsJwtValidator(
                    URI.create("https://issuer.test/finguardops"),
                    Clock.fixed(VALIDATION_TIME, ZoneOffset.UTC)
            );

    @Test
    void acceptsExactNormalizedSingletonAudience() {
        OAuth2TokenValidatorResult result = VALIDATOR.validate(jwt(List.of(
                FinGuardOpsSecurityProperties.AUDIENCE
        )));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsEveryNonSingletonOrNonExactNormalizedAudience() {
        List<List<String>> rejectedAudiences = List.of(
                List.of(),
                List.of("another-api"),
                List.of(
                        FinGuardOpsSecurityProperties.AUDIENCE,
                        "another-api"
                ),
                List.of(
                        "another-api",
                        FinGuardOpsSecurityProperties.AUDIENCE
                ),
                List.of(
                        FinGuardOpsSecurityProperties.AUDIENCE,
                        FinGuardOpsSecurityProperties.AUDIENCE
                )
        );

        rejectedAudiences.forEach(audience -> assertThat(
                VALIDATOR.validate(jwt(audience)).hasErrors()
        ).isTrue());
    }

    private Jwt jwt(List<String> audience) {
        return Jwt.withTokenValue("synthetic-validator-input")
                .header("alg", "RS256")
                .header("kid", "validator-test-key")
                .issuer("https://issuer.test/finguardops")
                .audience(audience)
                .subject("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001")
                .issuedAt(VALIDATION_TIME.minusSeconds(5))
                .expiresAt(VALIDATION_TIME.plusSeconds(300))
                .claim("principal_type", "USER")
                .claim("roles", List.of("FDS_VIEWER"))
                .build();
    }
}
