package com.aifds.backend.security.jwt;

import com.aifds.backend.security.config.FinGuardOpsSecurityConfiguration;
import com.aifds.backend.security.config.FinGuardOpsSecurityProperties;
import com.aifds.backend.security.support.EphemeralRsaJwtFixture;
import com.aifds.backend.security.support.InProcessJwkSetServer;
import com.nimbusds.jose.JWSAlgorithm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinGuardOpsJwtDecoderIntegrationTest {

    private static final Instant VALIDATION_TIME =
            Instant.parse("2026-09-03T00:00:00Z");
    private static final Clock VALIDATION_CLOCK =
            Clock.fixed(VALIDATION_TIME, ZoneOffset.UTC);
    private static final EphemeralRsaJwtFixture KEY_A =
            EphemeralRsaJwtFixture.create("key-a");
    private static final EphemeralRsaJwtFixture KEY_B =
            EphemeralRsaJwtFixture.create("key-b");
    private static final EphemeralRsaJwtFixture KEY_C =
            EphemeralRsaJwtFixture.create("key-c");

    private static InProcessJwkSetServer jwkServer;

    @BeforeAll
    static void startJwkServer() {
        jwkServer = InProcessJwkSetServer.start();
    }

    @AfterAll
    static void stopJwkServer() {
        jwkServer.close();
    }

    @BeforeEach
    void resetJwkServer() {
        jwkServer.serveKeys(KEY_A.publicJwk());
        jwkServer.resetRequestCount();
    }

    @Test
    void acceptsRawSingletonArrayAudienceForUserToken() {
        Jwt jwt = decoder().decode(KEY_A.validUserToken(VALIDATION_TIME));

        assertValidAudienceClaims(jwt, "USER", List.of("FDS_VIEWER"));
        assertThat(jwkServer.requestCount()).isEqualTo(1);
    }

    @Test
    void acceptsRawStringAudienceForUserToken() {
        Map<String, Object> claims = claims("USER", List.of("FDS_VIEWER"));
        claims.put("aud", FinGuardOpsSecurityProperties.AUDIENCE);

        Jwt jwt = decoder().decode(KEY_A.sign(claims));

        assertValidAudienceClaims(jwt, "USER", List.of("FDS_VIEWER"));
        assertThat(jwkServer.requestCount()).isEqualTo(1);
    }

    @Test
    void acceptsRawSingletonArrayAudienceForServiceToken() {
        Map<String, Object> claims = claims(
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        );

        Jwt jwt = decoder().decode(KEY_A.sign(claims));

        assertValidAudienceClaims(
                jwt,
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        );
        assertThat(jwkServer.requestCount()).isEqualTo(1);
    }

    @Test
    void acceptsRawStringAudienceForServiceToken() {
        Map<String, Object> claims = claims(
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        );
        claims.put("aud", FinGuardOpsSecurityProperties.AUDIENCE);

        Jwt jwt = decoder().decode(KEY_A.sign(claims));

        assertValidAudienceClaims(
                jwt,
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        );
        assertThat(jwkServer.requestCount()).isEqualTo(1);
    }

    @Test
    void acceptsServicePrincipalAndClockSkewBoundaries() {
        Map<String, Object> claims = claims(
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        );
        claims.put("iat", VALIDATION_TIME.plusSeconds(60).getEpochSecond());
        claims.put("nbf", VALIDATION_TIME.plusSeconds(60).getEpochSecond());
        claims.put("exp", VALIDATION_TIME.plusSeconds(899).getEpochSecond());

        assertThat(decoder().decode(KEY_A.sign(claims))).isNotNull();

        Map<String, Object> recentlyExpired = claims(
                "USER",
                List.of("FDS_VIEWER")
        );
        recentlyExpired.put(
                "iat",
                VALIDATION_TIME.minusSeconds(120).getEpochSecond()
        );
        recentlyExpired.put(
                "exp",
                VALIDATION_TIME.minusSeconds(60).getEpochSecond()
        );
        assertThat(decoder().decode(KEY_A.sign(recentlyExpired))).isNotNull();

        Map<String, Object> maximumLifetime = claims(
                "USER",
                List.of("FDS_VIEWER")
        );
        maximumLifetime.put(
                "iat",
                VALIDATION_TIME.minusSeconds(5).getEpochSecond()
        );
        maximumLifetime.put(
                "exp",
                VALIDATION_TIME.plusSeconds(895).getEpochSecond()
        );
        assertThat(decoder().decode(KEY_A.sign(maximumLifetime))).isNotNull();
    }

    @Test
    void rejectsNonRs256MissingKidUntrustedKeyUrlsAndWrongSignature() {
        assertRejected(KEY_A.sign(
                JWSAlgorithm.RS512,
                KEY_A.kid(),
                Map.of(),
                claims("USER", List.of("FDS_VIEWER"))
        ));
        assertRejected(KEY_A.sign(
                JWSAlgorithm.RS256,
                null,
                Map.of(),
                claims("USER", List.of("FDS_VIEWER"))
        ));
        assertRejected(KEY_A.sign(
                JWSAlgorithm.RS256,
                KEY_A.kid(),
                Map.of("jku", "https://attacker.example.test/jwks"),
                claims("USER", List.of("FDS_VIEWER"))
        ));
        assertRejected(KEY_A.sign(
                JWSAlgorithm.RS256,
                KEY_A.kid(),
                Map.of("x5u", "https://attacker.example.test/cert"),
                claims("USER", List.of("FDS_VIEWER"))
        ));
        assertRejected(KEY_B.validUserToken(VALIDATION_TIME));
        assertRejected("not-a-jwt");
    }

    @Test
    void rejectsInvalidRawAudienceRepresentations() {
        rejectMissingClaim("aud");
        rejectClaim("aud", null);
        rejectClaim("aud", "");
        rejectClaim("aud", " ");
        rejectClaim("aud", " finguardops-backend-api");
        rejectClaim("aud", "finguardops-backend-api ");
        rejectClaim("aud", "FINGUARDOPS-BACKEND-API");
        rejectClaim("aud", "another-api");
        rejectClaim("aud", List.of());
        rejectClaim("aud", List.of("another-api"));
        rejectClaim("aud", List.of(
                "finguardops-backend-api",
                "another-api"
        ));
        rejectClaim("aud", List.of(
                "another-api",
                "finguardops-backend-api"
        ));
        rejectClaim("aud", List.of(
                "finguardops-backend-api",
                "finguardops-backend-api"
        ));
        rejectClaim("aud", List.of(7));
        rejectClaim("aud", List.of("finguardops-backend-api", 7));
        rejectClaim("aud", List.of(List.of("finguardops-backend-api")));
        rejectClaim("aud", 7);
        rejectClaim("aud", true);
        rejectClaim("aud", Map.of());
        rejectClaim("aud", Map.of(
                "value",
                "finguardops-backend-api"
        ));
    }

    @Test
    void rejectsMalformedRawAudienceBeforeJwkResolution() {
        Map<String, Object> claims = claims("USER", List.of("FDS_VIEWER"));
        claims.put("aud", List.of("finguardops-backend-api", 7));

        assertRejected(KEY_A.sign(claims));

        assertThat(jwkServer.requestCount()).isZero();
    }

    @Test
    void rejectsInvalidIssuerAndSubjectMatrix() {
        rejectClaim("iss", "https://other-issuer.test/finguardops");
        rejectMissingClaim("iss");
        rejectClaim("sub", "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001");
        rejectClaim("sub", "2f4c0a4e-8a9d-3c2f-9a1b-7d6e5f430001");
        rejectClaim("sub", "2f4c0a4e-8a9d-4c2f-7a1b-7d6e5f430001");
        rejectClaim("sub", "not-a-uuid");
        rejectMissingClaim("sub");
    }

    @Test
    void rejectsPrincipalTypeAndRoleMatrix() {
        rejectMissingClaim("principal_type");
        rejectClaim("principal_type", "ADMIN");
        rejectClaim("principal_type", 7);
        rejectMissingClaim("roles");
        rejectClaim("roles", "FDS_VIEWER");
        rejectClaim("roles", List.of());
        rejectClaim("roles", List.of("FDS_VIEWER", "FDS_VIEWER"));
        rejectClaim("roles", List.of("UNKNOWN_ROLE"));
        rejectClaim("roles", List.of("FDS_VIEWER", 7));

        Map<String, Object> userWithServiceRole = claims(
                "USER",
                List.of("TRANSACTION_INGESTOR")
        );
        assertRejected(KEY_A.sign(userWithServiceRole));
        Map<String, Object> serviceWithUserRole = claims(
                "SERVICE",
                List.of("FDS_VIEWER")
        );
        assertRejected(KEY_A.sign(serviceWithUserRole));
    }

    @Test
    void rejectsMissingMalformedAndOutOfRangeTimeClaims() {
        rejectMissingClaim("iat");
        rejectMissingClaim("exp");
        rejectClaim("iat", "not-a-numeric-date");
        rejectClaim("exp", "not-a-numeric-date");
        rejectClaim("nbf", "not-a-numeric-date");

        Map<String, Object> futureIat = claims("USER", List.of("FDS_VIEWER"));
        futureIat.put(
                "iat",
                VALIDATION_TIME.plusSeconds(61).getEpochSecond()
        );
        futureIat.put(
                "exp",
                VALIDATION_TIME.plusSeconds(300).getEpochSecond()
        );
        assertRejected(KEY_A.sign(futureIat));

        Map<String, Object> futureNbf = claims("USER", List.of("FDS_VIEWER"));
        futureNbf.put(
                "nbf",
                VALIDATION_TIME.plusSeconds(61).getEpochSecond()
        );
        assertRejected(KEY_A.sign(futureNbf));

        Map<String, Object> expired = claims("USER", List.of("FDS_VIEWER"));
        expired.put(
                "iat",
                VALIDATION_TIME.minusSeconds(300).getEpochSecond()
        );
        expired.put(
                "exp",
                VALIDATION_TIME.minusSeconds(61).getEpochSecond()
        );
        assertRejected(KEY_A.sign(expired));

        Map<String, Object> excessiveLifetime = claims(
                "USER",
                List.of("FDS_VIEWER")
        );
        excessiveLifetime.put(
                "iat",
                VALIDATION_TIME.minusSeconds(5).getEpochSecond()
        );
        excessiveLifetime.put(
                "exp",
                VALIDATION_TIME.plusSeconds(896).getEpochSecond()
        );
        assertRejected(KEY_A.sign(excessiveLifetime));
    }

    @Test
    void cachesKnownKeyRotatesToNewKidAndRejectsReachableUnknownKid() {
        JwtDecoder decoder = decoder();
        assertThat(decoder.decode(KEY_A.validUserToken(VALIDATION_TIME)))
                .isNotNull();
        assertThat(jwkServer.requestCount()).isEqualTo(1);

        jwkServer.serveFailure(500, "upstream failure sentinel");
        assertThat(decoder.decode(KEY_A.validUserToken(VALIDATION_TIME)))
                .isNotNull();
        assertThat(jwkServer.requestCount()).isEqualTo(1);

        jwkServer.serveKeys(KEY_A.publicJwk(), KEY_B.publicJwk());
        assertThat(decoder.decode(KEY_B.validUserToken(VALIDATION_TIME)))
                .isNotNull();
        assertThat(jwkServer.requestCount()).isEqualTo(2);

        assertThatThrownBy(() -> decoder.decode(
                KEY_C.validUserToken(VALIDATION_TIME)
        ))
                .isInstanceOf(JwtException.class);
        assertThat(jwkServer.requestCount()).isEqualTo(3);
    }

    private JwtDecoder decoder() {
        return new FinGuardOpsSecurityConfiguration().jwtDecoder(
                new FinGuardOpsSecurityProperties(
                        URI.create(EphemeralRsaJwtFixture.ISSUER),
                        jwkServer.uri(),
                        List.of(),
                        Duration.ofMillis(250),
                        Duration.ofMillis(250),
                        true
                ),
                VALIDATION_CLOCK
        );
    }

    private Map<String, Object> claims(
            String principalType,
            List<?> roles
    ) {
        return new LinkedHashMap<>(KEY_A.validClaims(
                principalType,
                roles.stream().map(String::valueOf).toList(),
                VALIDATION_TIME
        ));
    }

    private void assertValidAudienceClaims(
            Jwt jwt,
            String principalType,
            List<String> roles
    ) {
        assertThat(jwt.getAudience()).containsExactly(
                FinGuardOpsSecurityProperties.AUDIENCE
        );
        assertThat(jwt.getSubject()).isEqualTo(
                EphemeralRsaJwtFixture.SUBJECT
        );
        assertThat(jwt.getClaimAsString("principal_type"))
                .isEqualTo(principalType);
        assertThat(jwt.getClaimAsStringList("roles"))
                .containsExactlyElementsOf(roles);
    }

    private void rejectClaim(String name, Object value) {
        Map<String, Object> claims = claims("USER", List.of("FDS_VIEWER"));
        claims.put(name, value);
        assertRejected(KEY_A.sign(claims));
    }

    private void rejectMissingClaim(String name) {
        Map<String, Object> claims = claims("USER", List.of("FDS_VIEWER"));
        claims.remove(name);
        assertRejected(KEY_A.sign(claims));
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder().decode(token))
                .isInstanceOf(JwtException.class);
    }
}
