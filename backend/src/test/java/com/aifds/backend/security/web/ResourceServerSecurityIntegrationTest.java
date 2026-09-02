package com.aifds.backend.security.web;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.security.jwt.FinGuardOpsJwtAuthenticationConverter;
import com.aifds.backend.security.principal.FinGuardOpsPrincipal;
import com.aifds.backend.security.support.EphemeralRsaJwtFixture;
import com.aifds.backend.security.support.InProcessJwkSetServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.RemoteKeySourceException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({
        ResourceServerSecurityIntegrationTest.SecurityProbeController.class,
        ResourceServerSecurityIntegrationTest.DeniedChainConfiguration.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc."
                        + "DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa."
                        + "HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway."
                        + "FlywayAutoConfiguration",
                "finguardops.security.allowed-origins="
                        + "https://console.example.test",
                "finguardops.security.jwk-connect-timeout=100ms",
                "finguardops.security.jwk-read-timeout=100ms",
                "finguardops.security.insecure-loopback-jwk-allowed=true"
        }
)
class ResourceServerSecurityIntegrationTest {

    private static final EphemeralRsaJwtFixture KEY_A =
            EphemeralRsaJwtFixture.create("full-stack-a");
    private static final EphemeralRsaJwtFixture KEY_B =
            EphemeralRsaJwtFixture.create("full-stack-b");
    private static final EphemeralRsaJwtFixture UNKNOWN_KEY =
            EphemeralRsaJwtFixture.create("full-stack-unknown");
    private static final EphemeralRsaJwtFixture FAILURE_KEY =
            EphemeralRsaJwtFixture.create("full-stack-failure");
    private static final EphemeralRsaJwtFixture TIMEOUT_KEY =
            EphemeralRsaJwtFixture.create("full-stack-timeout");
    private static final EphemeralRsaJwtFixture INVALID_JWK_BODY_KEY =
            EphemeralRsaJwtFixture.create("full-stack-invalid-jwk-body");

    private static final String PROTECTED_PATH = "/test/security/protected";
    private static final String DENIED_PATH = "/test/security/denied";
    private static final String TRACE_ID = "trace_security_contract_01";

    private static InProcessJwkSetServer jwkServer;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FinGuardOpsAuthenticationEntryPoint authenticationEntryPoint;

    @Autowired
    private RemoteJwkFailureClassifier failureClassifier;

    @BeforeAll
    static void startServer() {
        jwkServer = InProcessJwkSetServer.start();
        jwkServer.serveKeys(KEY_A.publicJwk());
    }

    @AfterAll
    static void stopServer() {
        jwkServer.close();
    }

    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "finguardops.security.issuer",
                () -> EphemeralRsaJwtFixture.ISSUER
        );
        registry.add(
                "finguardops.security.jwk-set-uri",
                () -> jwkServer.uri().toString()
        );
    }

    @Test
    @Order(1)
    void returnsExact401ForMissingMalformedAndInvalidCredentials()
            throws Exception {
        assertSecurityError(
                exchange(HttpMethod.GET, PROTECTED_PATH, headers(), null),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                FinGuardOpsAuthenticationEntryPoint.UNAUTHORIZED_MESSAGE,
                "Bearer realm=\"finguardops-backend\""
        );

        HttpHeaders malformed = headers();
        malformed.setBearerAuth("credential-sentinel-not-a-jwt");
        ResponseEntity<String> malformedResponse = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                malformed,
                null
        );
        assertSecurityError(
                malformedResponse,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                FinGuardOpsAuthenticationEntryPoint.UNAUTHORIZED_MESSAGE,
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\""
        );
        assertThat(malformedResponse.getBody()).doesNotContain(
                "credential-sentinel-not-a-jwt",
                "Authorization",
                "JwtException"
        );

        Map<String, Object> invalidClaims = KEY_A.validClaims(
                "USER",
                List.of("FDS_VIEWER")
        );
        invalidClaims.put("iss", "https://claim-secret.example.test");
        String invalidClaimToken = KEY_A.sign(invalidClaims);
        ResponseEntity<String> invalidClaim = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                bearer(invalidClaimToken),
                null
        );
        assertSecurityError(
                invalidClaim,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                FinGuardOpsAuthenticationEntryPoint.UNAUTHORIZED_MESSAGE,
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\""
        );
        assertThat(invalidClaim.getBody()).doesNotContain(
                "claim-secret",
                invalidClaimToken
        );

        HttpHeaders wrongSignature = bearer(KEY_B.validUserToken());
        assertSecurityError(
                exchange(HttpMethod.GET, PROTECTED_PATH, wrongSignature, null),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                FinGuardOpsAuthenticationEntryPoint.UNAUTHORIZED_MESSAGE,
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\""
        );

        String validToken = KEY_A.validUserToken();
        assertThat(exchange(
                HttpMethod.GET,
                PROTECTED_PATH + "?access_token=" + validToken,
                headers(),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders cookieToken = headers();
        cookieToken.add(HttpHeaders.COOKIE, "access_token=" + validToken);
        assertThat(exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                cookieToken,
                null
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders formToken = headers();
        formToken.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(exchange(
                HttpMethod.POST,
                PROTECTED_PATH,
                formToken,
                "access_token=" + validToken
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders duplicated = headers();
        duplicated.add(HttpHeaders.AUTHORIZATION, "Bearer token-one");
        duplicated.add(HttpHeaders.AUTHORIZATION, "Bearer token-two");
        assertSecurityError(
                exchange(HttpMethod.GET, PROTECTED_PATH, duplicated, null),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                FinGuardOpsAuthenticationEntryPoint.UNAUTHORIZED_MESSAGE,
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\""
        );
    }

    @Test
    @Order(2)
    void createsUserAndServicePrincipalsFromRealSignedTokens()
            throws Exception {
        ResponseEntity<String> user = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                bearer(KEY_A.validUserToken()),
                null
        );
        assertThat(user.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode userBody = objectMapper.readTree(user.getBody());
        assertThat(userBody.get("type").asText()).isEqualTo("USER");
        assertThat(userBody.get("name").asText()).isEqualTo(
                EphemeralRsaJwtFixture.SUBJECT
        );
        assertThat(userBody.get("authorities").toString())
                .contains("ROLE_FDS_VIEWER", "transaction:read")
                .doesNotContain("transaction:intake");
        assertThat(exchange(
                HttpMethod.GET,
                "/api/health",
                bearer(KEY_A.validUserToken()),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                HttpMethod.GET,
                "/actuator/health",
                bearer(KEY_A.validUserToken()),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                HttpMethod.GET,
                "/actuator/prometheus",
                bearer(KEY_A.validUserToken()),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        jwkServer.serveKeys(KEY_A.publicJwk(), KEY_B.publicJwk());
        String serviceToken = KEY_B.sign(KEY_B.validClaims(
                "SERVICE",
                List.of("BEHAVIOR_INGESTOR")
        ));
        ResponseEntity<String> service = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                bearer(serviceToken),
                null
        );
        assertThat(service.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.getBody())
                .contains("SERVICE", "ROLE_BEHAVIOR_INGESTOR")
                .doesNotContain("ROLE_FDS_VIEWER");
    }

    @Test
    @Order(3)
    void returnsExact403ForAuthenticatedPrincipalWithoutAuthority()
            throws Exception {
        ResponseEntity<String> response = exchange(
                HttpMethod.GET,
                DENIED_PATH,
                bearer(KEY_A.validUserToken()),
                null
        );

        assertSecurityError(
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                FinGuardOpsAccessDeniedHandler.ACCESS_DENIED_MESSAGE,
                "Bearer realm=\"finguardops-backend\", "
                        + "error=\"insufficient_scope\""
        );
    }

    @Test
    @Order(4)
    void enforcesStatelessCsrfAndCorsMatrix() {
        ResponseEntity<String> post = exchange(
                HttpMethod.POST,
                PROTECTED_PATH,
                bearer(KEY_A.validUserToken()),
                "{}"
        );
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post.getHeaders()).doesNotContainKey(HttpHeaders.SET_COOKIE);

        ResponseEntity<String> withoutTokenAgain = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                headers(),
                null
        );
        assertThat(withoutTokenAgain.getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders allowed = preflightHeaders("POST", "Authorization");
        ResponseEntity<String> allowedPreflight = exchange(
                HttpMethod.OPTIONS,
                PROTECTED_PATH,
                allowed,
                null
        );
        assertThat(allowedPreflight.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowedPreflight.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("https://console.example.test");
        assertThat(allowedPreflight.getHeaders()
                .getAccessControlAllowCredentials()).isFalse();

        HttpHeaders allowedSimple = bearer(KEY_A.validUserToken());
        allowedSimple.setOrigin("https://console.example.test");
        ResponseEntity<String> allowedSimpleResponse = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                allowedSimple,
                null
        );
        assertThat(allowedSimpleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowedSimpleResponse.getHeaders()
                .getAccessControlAllowOrigin())
                .isEqualTo("https://console.example.test");

        HttpHeaders deniedSimple = bearer(KEY_A.validUserToken());
        deniedSimple.setOrigin("https://attacker.example.test");
        ResponseEntity<String> deniedSimpleResponse = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                deniedSimple,
                null
        );
        assertThat(deniedSimpleResponse.getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deniedSimpleResponse.getHeaders()
                .getAccessControlAllowOrigin()).isNull();

        HttpHeaders deniedOrigin = preflightHeaders("POST", "Authorization");
        deniedOrigin.setOrigin("https://attacker.example.test");
        assertThat(exchange(
                HttpMethod.OPTIONS,
                PROTECTED_PATH,
                deniedOrigin,
                null
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(exchange(
                HttpMethod.OPTIONS,
                PROTECTED_PATH,
                preflightHeaders("DELETE", "Authorization"),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                HttpMethod.OPTIONS,
                PROTECTED_PATH,
                preflightHeaders("POST", "X-Unapproved-Header"),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @Order(5)
    @ExtendWith(OutputCaptureExtension.class)
    void preservesCachedKeyAndClassifiesUnknownKidAndJwkFailures(
            CapturedOutput output
    ) throws Exception {
        jwkServer.serveKeys(KEY_A.publicJwk());
        assertThat(exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                bearer(KEY_A.validUserToken()),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        jwkServer.serveFailure(500, "jwk-body-secret-sentinel");
        assertThat(exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                bearer(KEY_A.validUserToken()),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        jwkServer.serveKeys(KEY_A.publicJwk());
        assertSecurityError(
                exchange(
                        HttpMethod.GET,
                        PROTECTED_PATH,
                        bearer(UNKNOWN_KEY.validUserToken()),
                        null
                ),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                FinGuardOpsAuthenticationEntryPoint.UNAUTHORIZED_MESSAGE,
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\""
        );

        jwkServer.serveFailure(500, "jwk-body-secret-sentinel");
        String failureToken = FAILURE_KEY.validUserToken();
        ResponseEntity<String> unavailable = exchange(
                HttpMethod.GET,
                PROTECTED_PATH,
                bearer(failureToken),
                null
        );
        assertSecurityError(
                unavailable,
                HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE",
                FinGuardOpsAuthenticationEntryPoint.DEPENDENCY_UNAVAILABLE_MESSAGE,
                null
        );
        assertThat(unavailable.getBody()).doesNotContain(
                "jwk-body-secret-sentinel",
                jwkServer.uri().toString(),
                failureToken
        );

        jwkServer.serveFailure(200, "invalid-jwk-body-secret-sentinel");
        assertSecurityError(
                exchange(
                        HttpMethod.GET,
                        PROTECTED_PATH,
                        bearer(INVALID_JWK_BODY_KEY.validUserToken()),
                        null
                ),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                FinGuardOpsAuthenticationEntryPoint.INTERNAL_ERROR_MESSAGE,
                null
        );

        jwkServer.serveDelayedKeys(
                Duration.ofSeconds(1),
                KEY_A.publicJwk()
        );
        assertSecurityError(
                exchange(
                        HttpMethod.GET,
                        PROTECTED_PATH,
                        bearer(TIMEOUT_KEY.validUserToken()),
                        null
                ),
                HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_TIMEOUT",
                FinGuardOpsAuthenticationEntryPoint.DEPENDENCY_TIMEOUT_MESSAGE,
                null
        );
        assertThat(output.toString()).doesNotContain(
                "jwk-body-secret-sentinel",
                "invalid-jwk-body-secret-sentinel",
                jwkServer.uri().toString(),
                failureToken,
                "RemoteKeySourceException",
                "HttpServerErrorException"
        );
    }

    @Test
    @Order(7)
    void usesCauseChainAllowlistForConnectionDnsTlsAndTimeoutFailures() {
        assertThat(classifyRemote(new ConnectException("connection sentinel")))
                .isEqualTo(RemoteJwkFailureClassifier.Classification
                        .DEPENDENCY_UNAVAILABLE);
        assertThat(classifyRemote(new UnknownHostException("dns sentinel")))
                .isEqualTo(RemoteJwkFailureClassifier.Classification
                        .DEPENDENCY_UNAVAILABLE);
        assertThat(classifyRemote(new SSLException("tls sentinel")))
                .isEqualTo(RemoteJwkFailureClassifier.Classification
                        .DEPENDENCY_UNAVAILABLE);
        assertThat(classifyRemote(new SocketTimeoutException("timeout sentinel")))
                .isEqualTo(RemoteJwkFailureClassifier.Classification
                        .DEPENDENCY_TIMEOUT);
        assertThat(failureClassifier.classify(new IllegalStateException(
                "unexpected decoder sentinel"
        ))).isEqualTo(RemoteJwkFailureClassifier.Classification.INTERNAL_ERROR);
    }

    private RemoteJwkFailureClassifier.Classification classifyRemote(
            Exception cause
    ) {
        return failureClassifier.classify(new RemoteKeySourceException(
                "remote JWK failure",
                cause
        ));
    }

    @Test
    @Order(6)
    void rendersSafe500ForUnexpectedDecoderFailureWithoutChallenge()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI(PROTECTED_PATH);
        request.setAttribute(
                TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE,
                TRACE_ID
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticationEntryPoint.commence(
                request,
                response,
                new InvalidBearerTokenException(
                        "decoder failure",
                        new JwtException(
                                "internal-exception-secret-sentinel",
                                new IllegalStateException(
                                        "internal-cause-secret-sentinel"
                                )
                        )
                )
        );

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INTERNAL_ERROR\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"")
                .doesNotContain(
                        "internal-exception-secret-sentinel",
                        "internal-cause-secret-sentinel",
                        "JwtException",
                        "IllegalStateException"
                );
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID);
        return headers;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = headers();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders preflightHeaders(String method, String header) {
        HttpHeaders headers = headers();
        headers.setOrigin("https://console.example.test");
        headers.setAccessControlRequestMethod(HttpMethod.valueOf(method));
        headers.setAccessControlRequestHeaders(List.of(header));
        return headers;
    }

    private ResponseEntity<String> exchange(
            HttpMethod method,
            String path,
            HttpHeaders headers,
            String body
    ) {
        return restTemplate.exchange(
                "http://127.0.0.1:" + serverPort + path,
                method,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private void assertSecurityError(
            ResponseEntity<String> response,
            HttpStatus status,
            String code,
            String message,
            String challenge
    ) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst(
                TraceIdFilter.TRACE_ID_HEADER
        )).isEqualTo(TRACE_ID);
        assertThat(response.getHeaders().getFirst(
                HttpHeaders.WWW_AUTHENTICATE
        )).isEqualTo(challenge);
        assertThat(response.getHeaders().getContentType())
                .satisfies(contentType -> assertThat(contentType
                        .isCompatibleWith(MediaType.APPLICATION_JSON))
                        .isTrue());

        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "code",
                "message",
                "traceId",
                "fieldErrors"
        );
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("message").asText()).isEqualTo(message);
        assertThat(error.get("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(error.get("fieldErrors").isArray()).isTrue();
        assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping(PROTECTED_PATH)
        Map<String, Object> get(AbstractAuthenticationToken authentication) {
            FinGuardOpsPrincipal principal =
                    (FinGuardOpsPrincipal) authentication.getPrincipal();
            return Map.of(
                    "name",
                    principal.getName(),
                    "type",
                    principal.type().name(),
                    "authorities",
                    authentication.getAuthorities().stream()
                            .map(Object::toString)
                            .toList()
            );
        }

        @PostMapping(PROTECTED_PATH)
        Map<String, String> post() {
            return Map.of("status", "ok");
        }

        @GetMapping(DENIED_PATH)
        Map<String, String> denied() {
            return Map.of("status", "must-not-run");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeniedChainConfiguration {

        @Bean
        @org.springframework.core.annotation.Order(0)
        SecurityFilterChain deniedSecurityFilterChain(
                HttpSecurity http,
                JwtDecoder jwtDecoder,
                FinGuardOpsJwtAuthenticationConverter converter,
                FinGuardOpsAuthenticationEntryPoint entryPoint,
                FinGuardOpsAccessDeniedHandler deniedHandler
        ) throws Exception {
            http
                    .securityMatcher(DENIED_PATH)
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session ->
                            session.sessionCreationPolicy(
                                    SessionCreationPolicy.STATELESS
                            )
                    )
                    .authorizeHttpRequests(authorize -> authorize
                            .anyRequest().hasAuthority("test:never")
                    )
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler)
                    )
                    .oauth2ResourceServer(resourceServer -> resourceServer
                            .jwt(jwt -> jwt
                                    .decoder(jwtDecoder)
                                    .jwtAuthenticationConverter(converter)
                            )
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler)
                    );
            return http.build();
        }
    }
}
