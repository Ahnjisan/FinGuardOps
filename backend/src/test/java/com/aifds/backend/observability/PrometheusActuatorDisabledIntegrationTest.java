package com.aifds.backend.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
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
    private WebMvcEndpointHandlerMapping actuatorHandlerMapping;

    @Test
    void keepsPrometheusDisabledAndExistingHealthEndpointsAvailable() {
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

        assertThat(get("/actuator/prometheus").getStatusCode())
                .matches(status -> status.isError());

        ResponseEntity<String> actuatorHealth = get("/actuator/health");
        assertThat(actuatorHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actuatorHealth.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> apiHealth = get("/api/health");
        assertThat(apiHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiHealth.getBody())
                .contains("\"status\":\"UP\"", "\"service\":\"backend\"");

        ResponseEntity<String> discovery = get("/actuator");
        assertThat(discovery.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discovery.getBody()).contains("/actuator/health");
        Set.of("prometheus", "env", "beans", "metrics")
                .forEach(endpoint -> assertThat(discovery.getBody())
                        .doesNotContain("/actuator/" + endpoint));
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
