package com.aifds.backend.security.config;

import com.aifds.backend.security.jwt.FinGuardOpsJwtAuthenticationConverter;
import com.aifds.backend.security.jwt.FinGuardOpsJwtValidator;
import com.aifds.backend.security.web.FinGuardOpsAccessDeniedHandler;
import com.aifds.backend.security.web.FinGuardOpsAuthenticationEntryPoint;
import com.aifds.backend.security.web.RemoteJwkFailureClassifier;
import com.aifds.backend.security.web.SecurityErrorResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.aifds.backend.security.principal.FinGuardOpsAuthority.BEHAVIOR_EVENT_INTAKE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_AUDIT_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.TRANSACTION_INTAKE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.TRANSACTION_READ;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(FinGuardOpsSecurityProperties.class)
public class FinGuardOpsSecurityConfiguration {

    private static final List<CorsEndpoint> APPLICATION_CORS_ENDPOINTS = List.of(
            new CorsEndpoint(HttpMethod.GET, "/api/health"),
            new CorsEndpoint(HttpMethod.POST, "/api/v1/transactions"),
            new CorsEndpoint(HttpMethod.GET, "/api/v1/transactions"),
            new CorsEndpoint(
                    HttpMethod.GET,
                    "/api/v1/transactions/{transactionId}"
            ),
            new CorsEndpoint(HttpMethod.POST, "/api/v1/behavior-events"),
            new CorsEndpoint(HttpMethod.GET, "/api/v1/cases"),
            new CorsEndpoint(HttpMethod.GET, "/api/v1/cases/{caseId}"),
            new CorsEndpoint(
                    HttpMethod.PATCH,
                    "/api/v1/cases/{caseId}/status"
            ),
            new CorsEndpoint(
                    HttpMethod.PATCH,
                    "/api/v1/cases/{caseId}/assignee"
            ),
            new CorsEndpoint(
                    HttpMethod.POST,
                    "/api/v1/cases/{caseId}/resolution"
            ),
            new CorsEndpoint(
                    HttpMethod.POST,
                    "/api/v1/cases/{caseId}/notes"
            ),
            new CorsEndpoint(
                    HttpMethod.GET,
                    "/api/v1/cases/{caseId}/notes"
            ),
            new CorsEndpoint(
                    HttpMethod.GET,
                    "/api/v1/cases/{caseId}/audit-logs"
            )
    );
    private static final CorsEndpoint APPLICATION_ACTUATOR_HEALTH =
            new CorsEndpoint(HttpMethod.GET, "/actuator/health");

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            FinGuardOpsJwtAuthenticationConverter authenticationConverter,
            FinGuardOpsAuthenticationEntryPoint authenticationEntryPoint,
            FinGuardOpsAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource,
            Environment environment
    ) throws Exception {
        DefaultBearerTokenResolver bearerTokenResolver =
                new DefaultBearerTokenResolver();
        bearerTokenResolver.setAllowFormEncodedBodyParameter(false);
        bearerTokenResolver.setAllowUriQueryParameter(false);
        PathPatternRequestMatcher.Builder paths =
                PathPatternRequestMatcher.withDefaults();
        List<CorsEndpoint> corsEndpoints = applicationCorsEndpoints(
                environment
        );

        http
                .securityMatcher(request -> isApplicationListenerRequest(
                        request,
                        environment
                ))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(
                        corsConfigurationSource
                ))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .securityContext(context -> context
                        .securityContextRepository(
                                new NullSecurityContextRepository()
                        )
                )
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(approvedPreflightMatcher(
                                paths,
                                corsEndpoints
                        ))
                        .permitAll()
                        .requestMatchers(paths.matcher(
                                HttpMethod.GET,
                                "/api/health"
                        ))
                        .permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(paths.matcher(
                                HttpMethod.POST,
                                "/api/v1/transactions"
                        )).hasAuthority(TRANSACTION_INTAKE)
                        .requestMatchers(paths.matcher(
                                HttpMethod.POST,
                                "/api/v1/behavior-events"
                        )).hasAuthority(BEHAVIOR_EVENT_INTAKE)
                        .requestMatchers(paths.matcher(
                                HttpMethod.GET,
                                "/api/v1/transactions"
                        ), paths.matcher(
                                HttpMethod.GET,
                                "/api/v1/transactions/{transactionId}"
                        )).hasAuthority(TRANSACTION_READ)
                        .requestMatchers(paths.matcher(
                                HttpMethod.GET,
                                "/api/v1/cases"
                        ), paths.matcher(
                                HttpMethod.GET,
                                "/api/v1/cases/{caseId}"
                        )).hasAuthority(CASE_READ)
                        .requestMatchers(paths.matcher(
                                HttpMethod.GET,
                                "/api/v1/cases/{caseId}/notes"
                        )).hasAuthority(CASE_NOTE_READ)
                        .requestMatchers(paths.matcher(
                                HttpMethod.GET,
                                "/api/v1/cases/{caseId}/audit-logs"
                        )).hasAuthority(CASE_AUDIT_READ)
                        .requestMatchers(paths.matcher(
                                HttpMethod.PATCH,
                                "/api/v1/cases/{caseId}/status"
                        ), paths.matcher(
                                HttpMethod.PATCH,
                                "/api/v1/cases/{caseId}/assignee"
                        )).hasAuthority(CASE_WORKFLOW_WRITE)
                        .requestMatchers(paths.matcher(
                                HttpMethod.POST,
                                "/api/v1/cases/{caseId}/resolution"
                        )).hasAuthority(CASE_RESOLUTION_WRITE)
                        .requestMatchers(paths.matcher(
                                HttpMethod.POST,
                                "/api/v1/cases/{caseId}/notes"
                        )).hasAuthority(CASE_NOTE_WRITE)
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(
                                        authenticationConverter
                                )
                        )
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );
        return http.build();
    }

    private boolean isApplicationListenerRequest(
            HttpServletRequest request,
            Environment environment
    ) {
        if (ManagementPortType.get(environment)
                != ManagementPortType.DIFFERENT) {
            return true;
        }
        Integer managementPort = environment.getProperty(
                "local.management.port",
                Integer.class
        );
        if (managementPort == null || managementPort == 0) {
            managementPort = environment.getProperty(
                    "management.server.port",
                    Integer.class
            );
        }
        return managementPort == null
                || managementPort == 0
                || request.getLocalPort() != managementPort;
    }

    @Bean
    public JwtDecoder jwtDecoder(FinGuardOpsSecurityProperties properties) {
        return jwtDecoder(properties, Clock.systemUTC());
    }

    public JwtDecoder jwtDecoder(
            FinGuardOpsSecurityProperties properties,
            Clock clock
    ) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.jwkConnectTimeout());
        requestFactory.setReadTimeout(properties.jwkReadTimeout());

        NimbusJwtDecoder nimbus = NimbusJwtDecoder
                .withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        nimbus.setClaimSetConverter(strictClaimSetConverter());
        nimbus.setJwtValidator(new FinGuardOpsJwtValidator(properties, clock));

        return token -> {
            try {
                validateRawJwtShape(token);
                return nimbus.decode(token);
            } catch (BadJwtException exception) {
                throw exception;
            } catch (JwtException exception) {
                throw new BadJwtException("JWT validation failed", exception);
            } catch (RuntimeException exception) {
                throw new BadJwtException(
                        "JWT validation failed",
                        new JwtException("JWT decoder failed", exception)
                );
            }
        };
    }

    @Bean
    FinGuardOpsJwtAuthenticationConverter jwtAuthenticationConverter() {
        return new FinGuardOpsJwtAuthenticationConverter();
    }

    @Bean
    SecurityErrorResponseWriter securityErrorResponseWriter(
            ObjectMapper objectMapper
    ) {
        return new SecurityErrorResponseWriter(objectMapper);
    }

    @Bean
    RemoteJwkFailureClassifier remoteJwkFailureClassifier() {
        return new RemoteJwkFailureClassifier();
    }

    @Bean
    FinGuardOpsAuthenticationEntryPoint authenticationEntryPoint(
            SecurityErrorResponseWriter responseWriter,
            RemoteJwkFailureClassifier failureClassifier
    ) {
        return new FinGuardOpsAuthenticationEntryPoint(
                responseWriter,
                failureClassifier
        );
    }

    @Bean
    FinGuardOpsAccessDeniedHandler accessDeniedHandler(
            SecurityErrorResponseWriter responseWriter
    ) {
        return new FinGuardOpsAccessDeniedHandler(responseWriter);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            FinGuardOpsSecurityProperties properties,
            Environment environment
    ) {
        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        Map<String, List<String>> methodsByPath = new LinkedHashMap<>();
        for (CorsEndpoint endpoint : applicationCorsEndpoints(environment)) {
            methodsByPath.computeIfAbsent(
                    endpoint.path(),
                    ignored -> new ArrayList<>()
            ).add(endpoint.method().name());
        }
        methodsByPath.forEach((path, methods) -> source
                .registerCorsConfiguration(
                        path,
                        corsConfiguration(properties, methods)
                ));
        return source;
    }

    private CorsConfiguration corsConfiguration(
            FinGuardOpsSecurityProperties properties,
            List<String> methods
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.copyOf(methods));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "X-Trace-Id"
        ));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        return configuration;
    }

    private List<CorsEndpoint> applicationCorsEndpoints(
            Environment environment
    ) {
        if (ManagementPortType.get(environment) == ManagementPortType.DIFFERENT) {
            return APPLICATION_CORS_ENDPOINTS;
        }
        List<CorsEndpoint> endpoints = new ArrayList<>(
                APPLICATION_CORS_ENDPOINTS
        );
        endpoints.add(APPLICATION_ACTUATOR_HEALTH);
        return List.copyOf(endpoints);
    }

    private RequestMatcher approvedPreflightMatcher(
            PathPatternRequestMatcher.Builder paths,
            List<CorsEndpoint> endpoints
    ) {
        List<CorsEndpointMatcher> endpointMatchers = endpoints.stream()
                .map(endpoint -> new CorsEndpointMatcher(
                        endpoint.method(),
                        paths.matcher(endpoint.path())
                ))
                .toList();
        return request -> {
            if (!CorsUtils.isPreFlightRequest(request)) {
                return false;
            }
            HttpMethod requestedMethod;
            try {
                requestedMethod = HttpMethod.valueOf(request.getHeader(
                        HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD
                ));
            } catch (IllegalArgumentException exception) {
                return false;
            }
            return endpointMatchers.stream().anyMatch(endpoint ->
                    endpoint.method() == requestedMethod
                            && endpoint.pathMatcher().matches(request)
            );
        };
    }

    private record CorsEndpoint(HttpMethod method, String path) {
    }

    private record CorsEndpointMatcher(
            HttpMethod method,
            RequestMatcher pathMatcher
    ) {
    }

    private Converter<Map<String, Object>, Map<String, Object>>
            strictClaimSetConverter() {
        MappedJwtClaimSetConverter delegate =
                MappedJwtClaimSetConverter.withDefaults(Map.of());
        return claims -> {
            requireType(claims, "iss", String.class::isInstance);
            requireType(claims, "sub", String.class::isInstance);
            requireType(claims, "principal_type", String.class::isInstance);
            requireType(claims, "aud", List.class::isInstance);
            requireType(claims, "roles", List.class::isInstance);
            requireType(claims, "iat", this::isNumericDate);
            requireType(claims, "exp", this::isNumericDate);
            if (claims.containsKey("nbf")) {
                requireType(claims, "nbf", this::isNumericDate);
            }
            return delegate.convert(claims);
        };
    }

    private boolean isNumericDate(Object value) {
        return value instanceof Number || value instanceof Date;
    }

    private void requireType(
            Map<String, Object> claims,
            String name,
            Predicate<Object> expectedType
    ) {
        if (!claims.containsKey(name)
                || !expectedType.test(claims.get(name))) {
            throw new BadJwtException("JWT claim type is invalid");
        }
    }

    private void requireExactRawSingletonAudience(
            Map<String, Object> claims
    ) {
        if (!claims.containsKey("aud")
                || !isExactRawSingletonAudience(claims.get("aud"))) {
            throw new BadJwtException("JWT claim type is invalid");
        }
    }

    private boolean isExactRawSingletonAudience(Object value) {
        if (value instanceof String audience) {
            return FinGuardOpsSecurityProperties.AUDIENCE.equals(audience);
        }
        return value instanceof List<?> audiences
                && audiences.size() == 1
                && FinGuardOpsSecurityProperties.AUDIENCE.equals(
                audiences.get(0)
                );
    }

    private void validateRawJwtShape(String token) {
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3) {
                return;
            }
            Map<String, Object> claims = JSONObjectUtils.parse(
                    new Base64URL(segments[1]).decodeToString()
            );
            requireType(claims, "iss", String.class::isInstance);
            requireType(claims, "sub", String.class::isInstance);
            requireType(claims, "principal_type", String.class::isInstance);
            requireExactRawSingletonAudience(claims);
            requireType(claims, "roles", List.class::isInstance);
            requireType(claims, "iat", Number.class::isInstance);
            requireType(claims, "exp", Number.class::isInstance);
            if (claims.containsKey("nbf")) {
                requireType(claims, "nbf", Number.class::isInstance);
            }
        } catch (BadJwtException exception) {
            throw exception;
        } catch (RuntimeException | java.text.ParseException exception) {
            throw new BadJwtException("JWT payload is malformed");
        }
    }
}
