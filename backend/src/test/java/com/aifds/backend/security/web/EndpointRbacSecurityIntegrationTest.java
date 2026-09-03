package com.aifds.backend.security.web;

import com.aifds.backend.behavior.service.BehaviorEventIntakeService;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.service.FraudCaseAuditLogService;
import com.aifds.backend.fraudcase.service.FraudCaseQueryService;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.aifds.backend.fraudcase.service.InvestigationNoteService;
import com.aifds.backend.security.support.EphemeralRsaJwtFixture;
import com.aifds.backend.security.support.InProcessJwkSetServer;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aifds.backend.security.principal.FinGuardOpsAuthority.BEHAVIOR_EVENT_INTAKE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_AUDIT_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.TRANSACTION_INTAKE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.TRANSACTION_READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

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
                "finguardops.security.insecure-loopback-jwk-allowed=true"
        }
)
class EndpointRbacSecurityIntegrationTest {

    private static final String CASE_ID =
            "10000000-0000-4000-8000-000000000001";
    private static final String TRANSACTION_ID =
            "20000000-0000-4000-8000-000000000001";
    private static final String TRACE_ID = "trace_endpoint_rbac_01";
    private static final EphemeralRsaJwtFixture JWT =
            EphemeralRsaJwtFixture.create("endpoint-rbac");
    private static final List<String> USER_ROLES = List.of(
            "FDS_VIEWER",
            "FDS_ANALYST",
            "FDS_APPROVER",
            "RULE_OPERATOR",
            "RECOVERY_OPERATOR",
            "PLATFORM_ADMIN"
    );
    private static final List<String> SERVICE_ROLES = List.of(
            "TRANSACTION_INGESTOR",
            "BEHAVIOR_INGESTOR"
    );
    private static final List<Endpoint> ENDPOINTS = List.of(
            endpoint(HttpMethod.GET, "/api/health", null, null),
            endpoint(
                    HttpMethod.POST,
                    "/api/v1/transactions",
                    "TRANSACTION_INGESTOR",
                    TRANSACTION_INTAKE
            ),
            endpoint(
                    HttpMethod.POST,
                    "/api/v1/behavior-events",
                    "BEHAVIOR_INGESTOR",
                    BEHAVIOR_EVENT_INTAKE
            ),
            endpoint(
                    HttpMethod.GET,
                    "/api/v1/transactions",
                    "FDS_VIEWER",
                    TRANSACTION_READ
            ),
            endpoint(
                    HttpMethod.GET,
                    "/api/v1/transactions/" + TRANSACTION_ID,
                    "FDS_VIEWER",
                    TRANSACTION_READ
            ),
            endpoint(
                    HttpMethod.GET,
                    "/api/v1/cases",
                    "FDS_VIEWER",
                    CASE_READ
            ),
            endpoint(
                    HttpMethod.GET,
                    "/api/v1/cases/" + CASE_ID,
                    "FDS_VIEWER",
                    CASE_READ
            ),
            endpoint(
                    HttpMethod.GET,
                    "/api/v1/cases/" + CASE_ID + "/notes",
                    "FDS_VIEWER",
                    CASE_NOTE_READ
            ),
            endpoint(
                    HttpMethod.GET,
                    "/api/v1/cases/" + CASE_ID + "/audit-logs",
                    "FDS_VIEWER",
                    CASE_AUDIT_READ
            ),
            endpoint(
                    HttpMethod.PATCH,
                    "/api/v1/cases/" + CASE_ID + "/status",
                    "FDS_ANALYST",
                    CASE_WORKFLOW_WRITE
            ),
            endpoint(
                    HttpMethod.PATCH,
                    "/api/v1/cases/" + CASE_ID + "/assignee",
                    "FDS_ANALYST",
                    CASE_WORKFLOW_WRITE
            ),
            endpoint(
                    HttpMethod.POST,
                    "/api/v1/cases/" + CASE_ID + "/resolution",
                    "FDS_APPROVER",
                    CASE_RESOLUTION_WRITE
            ),
            endpoint(
                    HttpMethod.POST,
                    "/api/v1/cases/" + CASE_ID + "/notes",
                    "FDS_ANALYST",
                    CASE_NOTE_WRITE
            )
    );
    private static final List<CorsProbe> APPROVED_PREFLIGHTS = Stream.concat(
            ENDPOINTS.stream().map(endpoint -> new CorsProbe(
                    endpoint.path(),
                    endpoint.method(),
                    "https://console.example.test",
                    "Authorization",
                    true
            )),
            Stream.of(new CorsProbe(
                    "/actuator/health",
                    HttpMethod.GET,
                    "https://console.example.test",
                    "Authorization",
                    true
            ))
    ).toList();
    private static final List<CorsProbe> REJECTED_PREFLIGHTS = List.of(
            rejectedPreflight("/api/v1/detections", HttpMethod.GET),
            rejectedPreflight("/api/v1/behavior-events", HttpMethod.GET),
            rejectedPreflight("/api/v1/transactions", HttpMethod.DELETE),
            rejectedPreflight("/api/v1/transactions/", HttpMethod.POST),
            rejectedPreflight("/api/v1/cases/", HttpMethod.GET),
            rejectedPreflight(
                    "/api/v1/cases/" + CASE_ID + "/unknown",
                    HttpMethod.GET
            ),
            rejectedPreflight(
                    "/api/v1/cases/" + CASE_ID + "/resolution",
                    HttpMethod.PATCH
            ),
            rejectedPreflight(
                    "/api/v1/cases/" + CASE_ID + "/audit-logs",
                    HttpMethod.POST
            ),
            new CorsProbe(
                    "/api/v1/transactions",
                    HttpMethod.POST,
                    "https://attacker.example.test",
                    "Authorization",
                    true
            ),
            new CorsProbe(
                    "/api/v1/transactions",
                    HttpMethod.POST,
                    "https://console.example.test",
                    "X-Unapproved-Header",
                    true
            )
    );
    private static final List<EncodedPathProbe> ENCODED_PATHS = List.of(
            new EncodedPathProbe(
                    "/api/v1/transactions%2F" + TRANSACTION_ID,
                    400,
                    false
            ),
            new EncodedPathProbe(
                    "/api/v1/transactions/%2e%2e/transactions",
                    401,
                    true
            ),
            new EncodedPathProbe(
                    "/api/v1/transactions/%25",
                    401,
                    true
            ),
            new EncodedPathProbe(
                    "/api/v1/transactions;probe=1",
                    401,
                    true
            )
    );

    private static InProcessJwkSetServer jwkServer;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Autowired
    @Qualifier("applicationSecurityFilterChain")
    private SecurityFilterChain applicationSecurityFilterChain;

    @MockitoBean
    private TransactionIntakeService transactionIntakeService;

    @MockitoBean
    private BehaviorEventIntakeService behaviorEventIntakeService;

    @MockitoBean
    private TransactionQueryService transactionQueryService;

    @MockitoBean
    private FraudCaseQueryService fraudCaseQueryService;

    @MockitoBean
    private FraudCaseWorkflowService fraudCaseWorkflowService;

    @MockitoBean
    private InvestigationNoteService investigationNoteService;

    @MockitoBean
    private FraudCaseAuditLogService fraudCaseAuditLogService;

    @BeforeAll
    static void startJwkServer() {
        jwkServer = InProcessJwkSetServer.start();
        jwkServer.serveKeys(JWT.publicJwk());
    }

    @AfterAll
    static void stopJwkServer() {
        jwkServer.close();
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "finguardops.security.issuer",
                () -> EphemeralRsaJwtFixture.ISSUER
        );
        registry.add(
                "finguardops.security.jwk-set-uri",
                () -> jwkServer.uri().toString()
        );
    }

    @AfterEach
    void resetCollaborators() {
        reset(
                transactionIntakeService,
                behaviorEventIntakeService,
                transactionQueryService,
                fraudCaseQueryService,
                fraudCaseWorkflowService,
                investigationNoteService,
                fraudCaseAuditLogService
        );
    }

    @Test
    void coversExactlyThirteenProductionEndpointsAndMinimumRoles()
            throws Exception {
        assertThat(ENDPOINTS).hasSize(13);
        assertThat(ENDPOINTS.stream().map(Endpoint::signature))
                .doesNotHaveDuplicates();
        Set<String> actualMappings = requestMappingHandlerMapping
                .getHandlerMethods()
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().getBeanType()
                        .getPackageName().startsWith("com.aifds.backend"))
                .flatMap(entry -> entry.getKey().getMethodsCondition()
                        .getMethods().stream().flatMap(method -> entry.getKey()
                                .getPatternValues().stream().map(path ->
                                        method.name() + " " + path
                                )))
                .collect(Collectors.toSet());
        assertThat(actualMappings).containsExactlyInAnyOrderElementsOf(
                ENDPOINTS.stream()
                        .map(Endpoint::mappingSignature)
                        .collect(Collectors.toSet())
        );

        for (Endpoint endpoint : ENDPOINTS) {
            ResponseEntity<String> response = endpoint.role() == null
                    ? exchange(endpoint, headers(), endpoint.body())
                    : exchange(
                            endpoint,
                            bearer(token(typeOf(endpoint.role()), List.of(
                                    endpoint.role()
                            ))),
                            endpoint.body()
                    );
            assertThat(response.getStatusCode())
                    .as(endpoint.signature() + " requires "
                            + endpoint.authority())
                    .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getFirst(
                    TraceIdFilter.TRACE_ID_HEADER
            )).isEqualTo(TRACE_ID);
        }
    }

    @Test
    void rejectsRepresentativeRoleForEveryProtectedEndpointExactly()
            throws Exception {
        for (Endpoint endpoint : protectedEndpoints()) {
            String deniedRole = deniedRole(endpoint);
            ResponseEntity<String> response = exchange(
                    endpoint,
                    bearer(token(typeOf(deniedRole), List.of(deniedRole))),
                    endpoint.body()
            );
            assertSecurityError(response, HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED", "Bearer realm=\"finguardops-backend\", "
                            + "error=\"insufficient_scope\"");
        }
        verifyNoBusinessInteractions();
    }

    @Test
    void enforcesUserServiceAndIngestorCrossBoundaries() throws Exception {
        List<Endpoint> serviceEndpoints = ENDPOINTS.subList(1, 3);
        List<Endpoint> userEndpoints = ENDPOINTS.subList(3, ENDPOINTS.size());

        for (String role : USER_ROLES) {
            for (Endpoint endpoint : serviceEndpoints) {
                assertThat(exchange(
                        endpoint,
                        bearer(token("USER", List.of(role))),
                        endpoint.body()
                ).getStatusCode()).as(role + " -> " + endpoint.signature())
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
        for (String role : SERVICE_ROLES) {
            for (Endpoint endpoint : userEndpoints) {
                assertThat(exchange(
                        endpoint,
                        bearer(token("SERVICE", List.of(role))),
                        endpoint.body()
                ).getStatusCode()).as(role + " -> " + endpoint.signature())
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
        assertThat(exchange(
                serviceEndpoints.get(0),
                bearer(token("SERVICE", List.of("BEHAVIOR_INGESTOR"))),
                "{}"
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                serviceEndpoints.get(1),
                bearer(token("SERVICE", List.of("TRANSACTION_INGESTOR"))),
                "{}"
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoBusinessInteractions();
    }

    @Test
    void enforcesApprovedReadInheritanceWithoutOperatorOrAdminInheritance()
            throws Exception {
        List<Endpoint> reads = ENDPOINTS.subList(3, 9);
        for (String role : List.of(
                "FDS_VIEWER",
                "FDS_ANALYST",
                "FDS_APPROVER"
        )) {
            for (Endpoint endpoint : reads) {
                assertThat(exchange(
                        endpoint,
                        bearer(token("USER", List.of(role))),
                        null
                ).getStatusCode()).as(role + " -> " + endpoint.signature())
                        .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
            }
        }
        for (String role : List.of(
                "RULE_OPERATOR",
                "RECOVERY_OPERATOR",
                "PLATFORM_ADMIN"
        )) {
            for (Endpoint endpoint : reads) {
                assertThat(exchange(
                        endpoint,
                        bearer(token("USER", List.of(role))),
                        null
                ).getStatusCode()).as(role + " -> " + endpoint.signature())
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
    }

    @Test
    void enforcesAnalystApproverAndPlatformAdminWriteBoundaries()
            throws Exception {
        assertForbidden(11, "FDS_ANALYST");
        assertForbidden(9, "FDS_APPROVER");
        assertForbidden(10, "FDS_APPROVER");
        assertForbidden(12, "FDS_APPROVER");
        for (int index : List.of(1, 2, 9, 10, 11, 12)) {
            assertForbidden(index, "PLATFORM_ADMIN");
        }
        verifyNoBusinessInteractions();
    }

    @Test
    void unionsMultipleUserRolesAndIgnoresExternalAuthorityClaims()
            throws Exception {
        assertThat(exchange(
                ENDPOINTS.get(9),
                bearer(token("USER", List.of(
                        "FDS_ANALYST",
                        "FDS_APPROVER"
                ))),
                "{}"
        ).getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN);
        assertThat(exchange(
                ENDPOINTS.get(11),
                bearer(token("USER", List.of(
                        "FDS_ANALYST",
                        "FDS_APPROVER"
                ))),
                "{}"
        ).getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN);

        Map<String, Object> claims = new LinkedHashMap<>(
                JWT.validClaims("USER", List.of("FDS_VIEWER"))
        );
        claims.put("authorities", List.of(CASE_RESOLUTION_WRITE));
        claims.put("scope", CASE_RESOLUTION_WRITE);
        ResponseEntity<String> ignored = exchange(
                ENDPOINTS.get(11),
                bearer(JWT.sign(claims)),
                "{}"
        );
        assertSecurityError(ignored, HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", "Bearer realm=\"finguardops-backend\", "
                        + "error=\"insufficient_scope\"");
    }

    @Test
    void keepsMixedPrincipalRoleTokensAt401() throws Exception {
        assertSecurityError(exchange(
                ENDPOINTS.get(3),
                bearer(token("USER", List.of(
                        "FDS_VIEWER",
                        "TRANSACTION_INGESTOR"
                ))),
                null
        ), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\"");
        assertSecurityError(exchange(
                ENDPOINTS.get(1),
                bearer(token("SERVICE", List.of(
                        "TRANSACTION_INGESTOR",
                        "FDS_VIEWER"
                ))),
                "{}"
        ), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Bearer realm=\"finguardops-backend\", error=\"invalid_token\"");
    }

    @Test
    void deniesUnapprovedPathsMethodsAndTrailingSlashesByDefault()
            throws Exception {
        List<Endpoint> denied = List.of(
                endpoint(HttpMethod.GET, "/api/v1/transactions/", null, null),
                endpoint(HttpMethod.PUT, "/api/v1/transactions", null, null),
                endpoint(HttpMethod.GET, "/api/v1/detections", null, null),
                endpoint(HttpMethod.GET, "/api/v1/behavior-events", null, null),
                endpoint(HttpMethod.POST, "/api/v1/cases", null, null)
        );
        String viewer = token("USER", List.of("FDS_VIEWER"));
        for (Endpoint endpoint : denied) {
            assertThat(exchange(endpoint, bearer(viewer), endpoint.body())
                    .getStatusCode()).as(endpoint.signature())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(endpoint, headers(), endpoint.body())
                    .getStatusCode()).as("anonymous " + endpoint.signature())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        verifyNoBusinessInteractions();
    }

    @Test
    void rejectsEncodedSlashPeriodPercentAndSemicolonBeforeControllers()
            throws Exception {
        String viewer = token("USER", List.of("FDS_VIEWER"));
        assertThat(exchange(
                HttpMethod.GET,
                "/api/health",
                bearer(viewer),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        for (EncodedPathProbe probe : ENCODED_PATHS) {
            assertEncodedResponse(
                    probe,
                    RawAuthorization.ANONYMOUS,
                    rawAnonymousGet(probe.path()),
                    null
            );
            assertEncodedResponse(
                    probe,
                    RawAuthorization.VALID_BEARER,
                    rawBearerGet(probe.path(), viewer),
                    viewer
            );
        }
        verifyNoBusinessInteractions();
    }

    @Test
    void permitsEveryApprovedCorsPathAndMethodExactly() {
        assertThat(APPROVED_PREFLIGHTS).hasSize(14);
        assertThat(APPROVED_PREFLIGHTS.subList(0, 13))
                .extracting(CorsProbe::signature)
                .containsExactlyElementsOf(ENDPOINTS.stream()
                        .map(Endpoint::signature)
                        .toList());

        for (CorsProbe probe : APPROVED_PREFLIGHTS) {
            assertThat(preflightAuthorizationGranted(probe))
                    .as("authorization " + probe.signature())
                    .isTrue();
            ResponseEntity<String> response = exchange(
                    HttpMethod.OPTIONS,
                    probe.path(),
                    preflight(probe),
                    null
            );
            assertThat(response.getStatusCode())
                    .as(probe.signature()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getAccessControlAllowOrigin())
                    .isEqualTo("https://console.example.test");
            assertThat(response.getHeaders().getFirst(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS
            )).isNull();
            assertThat(response.getHeaders().getAccessControlAllowMethods())
                    .contains(probe.requestedMethod());
            assertThat(response.getHeaders().getAccessControlAllowHeaders())
                    .contains("Authorization");
            assertThat(response.getHeaders().getFirst(
                    TraceIdFilter.TRACE_ID_HEADER
            )).isEqualTo(TRACE_ID);
        }
        verifyNoBusinessInteractions();
    }

    @Test
    void rejectsEveryUnapprovedCorsPathMethodOriginAndHeader() {
        for (CorsProbe probe : REJECTED_PREFLIGHTS) {
            assertThat(preflightAuthorizationGranted(probe))
                    .as("authorization " + probe.signature())
                    .isEqualTo(probe.authorizationAllowed());
            ResponseEntity<String> response = exchange(
                    HttpMethod.OPTIONS,
                    probe.path(),
                    preflight(probe),
                    null
            );
            assertThat(response.getStatusCode())
                    .as(probe.signature()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getAccessControlAllowOrigin())
                    .as(probe.signature()).isNull();
            assertThat(response.getHeaders().getFirst(
                    TraceIdFilter.TRACE_ID_HEADER
            )).as(probe.signature()).isEqualTo(TRACE_ID);
        }
        verifyNoBusinessInteractions();
    }

    @Test
    void doesNotTreatOrdinaryOptionsAsPublicPreflight() {
        assertThat(exchange(
                HttpMethod.OPTIONS,
                "/api/v1/transactions",
                headers(),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(
                HttpMethod.OPTIONS,
                "/api/v1/transactions",
                bearer(token("SERVICE", List.of("TRANSACTION_INGESTOR"))),
                null
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoBusinessInteractions();
    }

    @Test
    void productionContextContainsNoTestOnlySecurityOrCorsBeans() {
        assertThat(applicationContext.containsBean(
                "testOnlySecurityFilterChain"
        )).isFalse();
        assertThat(applicationContext.containsBean(
                "deniedSecurityFilterChain"
        )).isFalse();
        assertThat(applicationContext.getBeansOfType(
                CorsConfigurationSource.class
        )).containsOnlyKeys(
                "corsConfigurationSource",
                "mvcHandlerMappingIntrospector"
        );
    }

    private void assertForbidden(int endpointIndex, String userRole)
            throws Exception {
        Endpoint endpoint = ENDPOINTS.get(endpointIndex);
        assertThat(exchange(
                endpoint,
                bearer(token("USER", List.of(userRole))),
                endpoint.body()
        ).getStatusCode()).as(userRole + " -> " + endpoint.signature())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private List<Endpoint> protectedEndpoints() {
        return ENDPOINTS.subList(1, ENDPOINTS.size());
    }

    private String deniedRole(Endpoint endpoint) {
        if (endpoint.role().equals("TRANSACTION_INGESTOR")) {
            return "BEHAVIOR_INGESTOR";
        }
        if (endpoint.role().equals("BEHAVIOR_INGESTOR")) {
            return "TRANSACTION_INGESTOR";
        }
        if (endpoint.authority().endsWith(":read")) {
            return "RULE_OPERATOR";
        }
        if (endpoint.authority().equals(CASE_RESOLUTION_WRITE)) {
            return "FDS_ANALYST";
        }
        if (endpoint.authority().equals(CASE_NOTE_WRITE)) {
            return "FDS_APPROVER";
        }
        return "FDS_VIEWER";
    }

    private String typeOf(String role) {
        return SERVICE_ROLES.contains(role) ? "SERVICE" : "USER";
    }

    private String token(String type, List<String> roles) {
        return JWT.sign(JWT.validClaims(type, roles));
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

    private HttpHeaders preflight(CorsProbe probe) {
        HttpHeaders headers = headers();
        headers.setOrigin(probe.origin());
        headers.setAccessControlRequestMethod(probe.requestedMethod());
        headers.setAccessControlRequestHeaders(List.of(probe.requestedHeader()));
        return headers;
    }

    private boolean preflightAuthorizationGranted(CorsProbe probe) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                HttpMethod.OPTIONS.name(),
                probe.path()
        );
        request.setServletPath(probe.path());
        request.addHeader(HttpHeaders.ORIGIN, probe.origin());
        request.addHeader(
                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                probe.requestedMethod().name()
        );
        request.addHeader(
                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                probe.requestedHeader()
        );
        List<AuthorizationFilter> filters = applicationSecurityFilterChain
                .getFilters().stream()
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .toList();
        assertThat(filters).hasSize(1);
        return filters.get(0).getAuthorizationManager()
                .authorize(() -> null, request)
                .isGranted();
    }

    private ResponseEntity<String> exchange(
            Endpoint endpoint,
            HttpHeaders headers,
            String body
    ) {
        return exchange(endpoint.method(), endpoint.path(), headers, body);
    }

    private ResponseEntity<String> exchange(
            HttpMethod method,
            String path,
            HttpHeaders headers,
            String body
    ) {
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (method == HttpMethod.POST
                    && path.equals("/api/v1/transactions")) {
                headers.set("Idempotency-Key", "endpoint-rbac-probe");
            }
        }
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
            String challenge
    ) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst(
                TraceIdFilter.TRACE_ID_HEADER
        )).isEqualTo(TRACE_ID);
        assertThat(response.getHeaders().getFirst(
                HttpHeaders.WWW_AUTHENTICATE
        )).isEqualTo(challenge);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "code", "message", "traceId", "fieldErrors"
        );
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(body.get("fieldErrors").isEmpty()).isTrue();
        assertThat(response.getBody()).doesNotContain(
                "roles",
                "authorities",
                "principal_type",
                "JwtException",
                "AccessDeniedException"
        );
    }

    private RawResponse rawAnonymousGet(String rawPath) throws Exception {
        return rawGet(rawPath, RawAuthorization.ANONYMOUS, null);
    }

    private RawResponse rawBearerGet(String rawPath, String token)
            throws Exception {
        return rawGet(
                rawPath,
                RawAuthorization.VALID_BEARER,
                Objects.requireNonNull(token, "token must not be null")
        );
    }

    private RawResponse rawGet(
            String rawPath,
            RawAuthorization authorization,
            String token
    ) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", serverPort);
                OutputStreamWriter writer = new OutputStreamWriter(
                        socket.getOutputStream(),
                        StandardCharsets.US_ASCII
                );
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.ISO_8859_1
                        )
                )) {
            StringBuilder request = new StringBuilder()
                    .append("GET ").append(rawPath).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(serverPort)
                    .append("\r\n");
            if (authorization == RawAuthorization.VALID_BEARER) {
                request.append("Authorization: Bearer ")
                        .append(token).append("\r\n");
            }
            request.append("X-Trace-Id: ").append(TRACE_ID).append("\r\n")
                    .append("Connection: close\r\n\r\n");
            boolean authorizationHeaderSent = request.indexOf(
                    "Authorization: Bearer "
            ) >= 0;
            writer.write(request.toString());
            writer.flush();

            String statusLine = reader.readLine();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    responseHeaders.put(
                            line.substring(0, separator).toLowerCase(),
                            line.substring(separator + 1).trim()
                    );
                }
            }
            StringBuilder responseBody = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                responseBody.append(line).append('\n');
            }
            return new RawResponse(
                    status,
                    responseHeaders,
                    responseBody.toString(),
                    authorizationHeaderSent
            );
        }
    }

    private void assertEncodedResponse(
            EncodedPathProbe probe,
            RawAuthorization authorization,
            RawResponse response,
            String bearerToken
    ) {
        assertThat(response.status())
                .as(authorization + " " + probe.path())
                .isEqualTo(probe.expectedStatus())
                .isNotEqualTo(404);
        assertThat(response.status() < 200 || response.status() > 299)
                .as(authorization + " " + probe.path() + " is not 2xx")
                .isTrue();
        assertThat(response.headers().get("x-trace-id"))
                .as(authorization + " " + probe.path())
                .isEqualTo(probe.traceExpected() ? TRACE_ID : null);
        assertThat(response.authorizationHeaderSent())
                .isEqualTo(authorization == RawAuthorization.VALID_BEARER);
        if (bearerToken != null) {
            assertThat(response.body()).doesNotContain(bearerToken);
        }
        assertThat(response.body()).doesNotContain(
                "principal_type",
                "roles",
                "authorities",
                "JwtException",
                "AccessDeniedException",
                "RequestRejectedException"
        );
    }

    private void verifyNoBusinessInteractions() {
        verifyNoInteractions(
                transactionIntakeService,
                behaviorEventIntakeService,
                transactionQueryService,
                fraudCaseQueryService,
                fraudCaseWorkflowService,
                investigationNoteService,
                fraudCaseAuditLogService
        );
    }

    private static Endpoint endpoint(
            HttpMethod method,
            String path,
            String role,
            String authority
    ) {
        String body = method == HttpMethod.POST || method == HttpMethod.PATCH
                ? "{}"
                : null;
        return new Endpoint(method, path, role, authority, body);
    }

    private static CorsProbe rejectedPreflight(
            String path,
            HttpMethod requestedMethod
    ) {
        return new CorsProbe(
                path,
                requestedMethod,
                "https://console.example.test",
                "Authorization",
                false
        );
    }

    private record Endpoint(
            HttpMethod method,
            String path,
            String role,
            String authority,
            String body
    ) {
        String signature() {
            return method + " " + path;
        }

        String mappingSignature() {
            return signature()
                    .replace(CASE_ID, "{caseId}")
                    .replace(TRANSACTION_ID, "{transactionId}");
        }
    }

    private record RawResponse(
            int status,
            Map<String, String> headers,
            String body,
            boolean authorizationHeaderSent
    ) {
    }

    private record CorsProbe(
            String path,
            HttpMethod requestedMethod,
            String origin,
            String requestedHeader,
            boolean authorizationAllowed
    ) {
        String signature() {
            return requestedMethod + " " + path;
        }
    }

    private record EncodedPathProbe(
            String path,
            int expectedStatus,
            boolean traceExpected
    ) {
    }

    private enum RawAuthorization {
        ANONYMOUS,
        VALID_BEARER
    }
}
