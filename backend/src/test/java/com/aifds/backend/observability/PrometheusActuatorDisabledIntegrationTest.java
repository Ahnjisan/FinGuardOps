package com.aifds.backend.observability;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
                        + "FlywayAutoConfiguration"
        }
)
class PrometheusActuatorDisabledIntegrationTest {

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebMvcEndpointHandlerMapping actuatorHandlerMapping;

    @Test
    void keepsPrometheusDisabledAndExistingHealthEndpointsAvailable()
            throws Exception {
        assertThat(environment.getActiveProfiles())
                .doesNotContain("prometheus");
        assertThat(environment.getProperty(
                "management.prometheus.metrics.export.enabled",
                Boolean.class
        )).isFalse();
        assertThat(applicationContext.getBeansOfType(
                PrometheusMeterRegistry.class
        )).isEmpty();

        assertThat(actuatorEndpointRoots(actuatorHandlerMapping))
                .containsExactly("health");

        String requestQuerySentinel = "TEST_ONLY_PROMETHEUS_QUERY_SENTINEL";
        ResponseEntity<String> prometheus = get(
                "/actuator/prometheus?probe=" + requestQuerySentinel
        );
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(prometheus.getBody()).isNotNull();
        JsonNode error = objectMapper.readTree(prometheus.getBody());
        assertThat(error.get("code").asText())
                .isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(error.get("message").asText())
                .isEqualTo("요청한 리소스를 찾을 수 없습니다.");
        assertThat(error.get("fieldErrors").isArray()).isTrue();
        assertThat(error.get("fieldErrors").size()).isZero();
        assertThat(error.get("traceId").asText()).isNotBlank();
        assertThat(prometheus.getHeaders().getFirst(
                TraceIdFilter.TRACE_ID_HEADER
        )).isEqualTo(error.get("traceId").asText());
        assertThat(prometheus.getBody()).doesNotContain(
                "/actuator/prometheus",
                requestQuerySentinel,
                "NoResourceFoundException",
                "No static resource",
                "org.springframework.web.servlet.resource",
                "\tat "
        );

        ResponseEntity<String> actuatorHealth = get("/actuator/health");
        assertThat(actuatorHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(actuatorHealth.getBody()).fieldNames())
                .toIterable()
                .containsExactly("status");

        ResponseEntity<String> apiHealth = get("/api/health");
        assertThat(apiHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiHealth.getBody())
                .contains("\"status\":\"UP\"", "\"service\":\"backend\"");

        assertThat(get("/actuator").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        HttpHeaders invalidBearer = new HttpHeaders();
        invalidBearer.setBearerAuth("not-a-jwt");
        ResponseEntity<String> invalidHealth = restTemplate.exchange(
                "http://127.0.0.1:" + serverPort + "/actuator/health",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(invalidBearer),
                String.class
        );
        assertThat(invalidHealth.getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalidHealth.getBody()).contains("\"code\":\"UNAUTHORIZED\"");

        HttpHeaders preflight = new HttpHeaders();
        preflight.setOrigin("https://unconfigured.example.test");
        preflight.setAccessControlRequestMethod(
                org.springframework.http.HttpMethod.GET
        );
        ResponseEntity<String> defaultCors = restTemplate.exchange(
                "http://127.0.0.1:" + serverPort + "/api/health",
                org.springframework.http.HttpMethod.OPTIONS,
                new org.springframework.http.HttpEntity<>(preflight),
                String.class
        );
        assertThat(defaultCors.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(defaultCors.getHeaders().getAccessControlAllowOrigin())
                .isNull();
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.getForEntity(
                "http://127.0.0.1:" + serverPort + path,
                String.class
        );
    }

    private Set<String> actuatorEndpointRoots(
            WebMvcEndpointHandlerMapping handlerMapping
    ) {
        String basePath = environment.getProperty(
                "management.endpoints.web.base-path",
                "/actuator"
        );
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .map(path -> endpointRoot(path, basePath))
                .filter(root -> !root.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String endpointRoot(String path, String basePath) {
        String prefix = basePath.endsWith("/")
                ? basePath
                : basePath + "/";
        if (path.equals(basePath) || path.equals(prefix)) {
            return "";
        }
        assertThat(path).startsWith(prefix);
        return path.substring(prefix.length()).split("/", 2)[0];
    }
}
