package com.aifds.backend.observability;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("prometheus")
@Import(PrometheusActuatorEnabledIntegrationTest
        .ManagementContextCaptureConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
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
class PrometheusActuatorEnabledIntegrationTest {

    private static final Set<String> COUNTER_NAMES = Set.of(
            "finguardops_transaction_intake_outcomes_total",
            "finguardops_transactions_received_total",
            "finguardops_transaction_outcomes_total",
            "finguardops_http_duplicate_requests_total",
            "finguardops_http_idempotency_conflicts_total",
            "finguardops_external_risk_outcomes_total",
            "finguardops_rule_analysis_outcomes_total"
    );
    private static final Set<String> TIMER_NAMES = Set.of(
            "finguardops_transaction_processing_duration_seconds",
            "finguardops_external_risk_duration_seconds",
            "finguardops_rule_analysis_duration_seconds"
    );
    private static final Set<String> UNEXPOSED_ENDPOINTS = Set.of(
            "env",
            "beans",
            "configprops",
            "mappings",
            "metrics",
            "loggers",
            "heapdump",
            "threaddump"
    );

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private Environment environment;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Autowired
    private MicrometerTransactionProcessingMetricsRecorder metricsRecorder;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ManagementContextCapture managementContextCapture;

    @Test
    void exposesOnlyHealthAndPrometheusOnSeparateManagementPort() {
        assertThat(environment.getActiveProfiles())
                .containsExactly("prometheus");
        assertThat(environment.getProperty(
                "management.prometheus.metrics.export.enabled",
                Boolean.class
        )).isTrue();
        assertThat(prometheusMeterRegistry).isNotNull();
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

        ServletWebServerApplicationContext managementContext =
                managementContextCapture.getManagementContext();
        assertThat(managementContext.getWebServer().getPort())
                .isEqualTo(managementPort);
        assertThat(actuatorEndpointRoots(
                managementContext.getBean(
                        WebMvcEndpointHandlerMapping.class
                ),
                managementContext.getEnvironment()
                        .getProperty(
                                "management.endpoints.web.base-path",
                                "/actuator"
                        )
        )).containsExactlyInAnyOrder("health", "prometheus");

        ResponseEntity<String> health = getManagement("/actuator/health");
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        recordAllApprovedMeters();
        ResponseEntity<String> scrape = getManagement(
                "/actuator/prometheus"
        );
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertPrometheusCompatible(scrape.getHeaders());
        assertThat(scrape.getBody()).isNotNull();
        COUNTER_NAMES.forEach(name -> assertThat(scrape.getBody())
                .contains("# TYPE " + name + " counter", name + "{"));
        TIMER_NAMES.forEach(name -> assertThat(scrape.getBody())
                .contains(
                        name + "_count{",
                        name + "_sum{"
                ));

        ResponseEntity<String> discovery = getManagement("/actuator");
        assertThat(discovery.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discovery.getBody())
                .contains("/actuator/health", "/actuator/prometheus");
        UNEXPOSED_ENDPOINTS.forEach(endpoint -> {
            assertThat(getManagement("/actuator/" + endpoint).getStatusCode())
                    .matches(status -> status.isError());
            assertThat(discovery.getBody())
                    .doesNotContain("/actuator/" + endpoint);
        });
    }

    private void recordAllApprovedMeters() {
        metricsRecorder.recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome.ACCEPTED
        );
        metricsRecorder.recordTransactionReceived();
        metricsRecorder.recordTransactionTerminal(
                TransactionProcessingStatus.APPROVED,
                RiskLevel.LOW,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofMillis(25)
        );
        metricsRecorder.recordDuplicateRequest(
                TransactionProcessingMetricsRecorder.DuplicateResult.COMPLETED
        );
        metricsRecorder.recordIdempotencyConflict();
        metricsRecorder.recordExternalRisk(
                TransactionProcessingMetricsRecorder.ExternalRiskResult.MATCHED,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofMillis(5)
        );
        metricsRecorder.recordRuleAnalysis(
                TransactionProcessingMetricsRecorder.RuleResult.COMPLETED,
                RiskLevel.HIGH,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofMillis(10)
        );
    }

    private void assertPrometheusCompatible(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.toString()).satisfiesAnyOf(
                value -> assertThat(value).startsWith("text/plain"),
                value -> assertThat(value)
                        .startsWith("application/openmetrics-text")
        );
    }

    private ResponseEntity<String> getManagement(String path) {
        return restTemplate.getForEntity(
                "http://127.0.0.1:" + managementPort + path,
                String.class
        );
    }

    private Set<String> actuatorEndpointRoots(
            WebMvcEndpointHandlerMapping handlerMapping,
            String basePath
    ) {
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

    @TestConfiguration(proxyBeanMethods = false)
    static class ManagementContextCaptureConfiguration {

        @Bean
        ManagementContextCapture managementContextCapture() {
            return new ManagementContextCapture();
        }
    }

    static final class ManagementContextCapture implements
            ApplicationListener<ServletWebServerInitializedEvent> {

        private volatile ServletWebServerApplicationContext managementContext;

        @Override
        public void onApplicationEvent(
                ServletWebServerInitializedEvent event
        ) {
            ServletWebServerApplicationContext context =
                    event.getApplicationContext();
            if ("management".equals(context.getServerNamespace())) {
                managementContext = context;
            }
        }

        ServletWebServerApplicationContext getManagementContext() {
            assertThat(managementContext).isNotNull();
            return managementContext;
        }
    }
}
