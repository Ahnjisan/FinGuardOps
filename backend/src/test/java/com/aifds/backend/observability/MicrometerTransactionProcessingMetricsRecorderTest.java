package com.aifds.backend.observability;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MicrometerTransactionProcessingMetricsRecorderTest {

    @Test
    void registersApprovedNamesTypesDescriptionsUnitsAndLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTransactionProcessingMetricsRecorder recorder =
                new MicrometerTransactionProcessingMetricsRecorder(registry);

        recorder.recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome.ACCEPTED
        );
        recorder.recordTransactionReceived();
        recorder.recordTransactionTerminal(
                TransactionProcessingStatus.APPROVED,
                RiskLevel.LOW,
                TransactionProcessingMetricsRecorder.FailureCategory.UNKNOWN,
                Duration.ofMillis(25)
        );
        recorder.recordDuplicateRequest(
                TransactionProcessingMetricsRecorder.DuplicateResult.COMPLETED
        );
        recorder.recordIdempotencyConflict();
        recorder.recordExternalRisk(
                TransactionProcessingMetricsRecorder.ExternalRiskResult.MATCHED,
                TransactionProcessingMetricsRecorder.FailureCategory.UNKNOWN,
                Duration.ofMillis(5)
        );
        recorder.recordRuleAnalysis(
                TransactionProcessingMetricsRecorder.RuleResult.COMPLETED,
                RiskLevel.HIGH,
                TransactionProcessingMetricsRecorder.FailureCategory.UNKNOWN,
                Duration.ofMillis(10)
        );

        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .INTAKE_OUTCOMES, "accepted");
        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTIONS_RECEIVED, "received");
        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTION_OUTCOMES, null);
        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .DUPLICATE_REQUESTS, "completed");
        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .IDEMPOTENCY_CONFLICTS, "conflict");
        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .EXTERNAL_RISK_OUTCOMES, "matched");
        assertCounter(registry, MicrometerTransactionProcessingMetricsRecorder
                .RULE_ANALYSIS_OUTCOMES, "completed");
        assertTimer(registry, MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTION_PROCESSING_DURATION);
        assertTimer(registry, MicrometerTransactionProcessingMetricsRecorder
                .EXTERNAL_RISK_DURATION);
        assertTimer(registry, MicrometerTransactionProcessingMetricsRecorder
                .RULE_ANALYSIS_DURATION);

        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTag("service"))
                    .isEqualTo("spring-backend");
            assertThat(meter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .doesNotContain(
                            "transactionId", "idempotencyKey", "fingerprint",
                            "traceId", "provider", "deploymentVersion", "path",
                            "url", "exception", "message"
                    );
        });
    }

    @Test
    void prometheusScrapeUsesExactApprovedCounterAndTimerNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT
        );
        MicrometerTransactionProcessingMetricsRecorder recorder =
                new MicrometerTransactionProcessingMetricsRecorder(registry);

        recorder.recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome.ACCEPTED
        );
        recorder.recordTransactionReceived();
        recorder.recordTransactionTerminal(
                TransactionProcessingStatus.APPROVED,
                RiskLevel.LOW,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofMillis(25)
        );
        recorder.recordDuplicateRequest(
                TransactionProcessingMetricsRecorder.DuplicateResult.COMPLETED
        );
        recorder.recordIdempotencyConflict();
        recorder.recordExternalRisk(
                TransactionProcessingMetricsRecorder.ExternalRiskResult.MATCHED,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofMillis(5)
        );
        recorder.recordRuleAnalysis(
                TransactionProcessingMetricsRecorder.RuleResult.COMPLETED,
                RiskLevel.HIGH,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofMillis(10)
        );

        String scrape = registry.scrape();
        Map<String, String> counters = Map.of(
                "finguardops_transaction_intake_outcomes_total",
                "result=\"accepted\"",
                "finguardops_transactions_received_total",
                "result=\"received\"",
                "finguardops_transaction_outcomes_total",
                "status=\"APPROVED\"",
                "finguardops_http_duplicate_requests_total",
                "result=\"completed\"",
                "finguardops_http_idempotency_conflicts_total",
                "result=\"conflict\"",
                "finguardops_external_risk_outcomes_total",
                "result=\"matched\"",
                "finguardops_rule_analysis_outcomes_total",
                "result=\"completed\""
        );
        counters.forEach((name, tag) -> assertScrapedCounter(
                scrape,
                name,
                tag
        ));

        Set.of(
                "finguardops_transaction_processing_duration_seconds",
                "finguardops_external_risk_duration_seconds",
                "finguardops_rule_analysis_duration_seconds"
        ).forEach(name -> assertScrapedTimer(scrape, name));
        assertThat(scrape).doesNotContain(
                "finguardops_transaction_intake_outcomes_requests_total",
                "finguardops_transactions_received_transactions_total",
                "finguardops_transaction_outcomes_transactions_total",
                "finguardops_http_duplicate_requests_requests_total",
                "finguardops_http_idempotency_conflicts_requests_total",
                "finguardops_external_risk_outcomes_attempts_total",
                "finguardops_rule_analysis_outcomes_attempts_total"
        );
    }

    @Test
    void mapsSuccessToNoneAndOnlyUnclassifiedFailuresToUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTransactionProcessingMetricsRecorder recorder =
                new MicrometerTransactionProcessingMetricsRecorder(registry);

        recorder.recordTransactionTerminal(
                TransactionProcessingStatus.HELD,
                RiskLevel.CRITICAL,
                TransactionProcessingMetricsRecorder.FailureCategory.UNKNOWN,
                Duration.ZERO
        );
        recorder.recordTransactionTerminal(
                TransactionProcessingStatus.FAILED,
                null,
                null,
                Duration.ofMillis(1)
        );
        recorder.recordExternalRisk(
                TransactionProcessingMetricsRecorder.ExternalRiskResult
                        .UNMATCHED,
                TransactionProcessingMetricsRecorder.FailureCategory.UNKNOWN,
                Duration.ZERO
        );
        recorder.recordRuleAnalysis(
                TransactionProcessingMetricsRecorder.RuleResult.FAILED,
                null,
                null,
                Duration.ZERO
        );

        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTION_OUTCOMES).tag("status", "HELD")
                .tag("failureCategory", "none").counter()).isNotNull();
        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTION_OUTCOMES).tag("status", "FAILED")
                .tag("riskLevel", "unknown")
                .tag("failureCategory", "unknown").counter()).isNotNull();
        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .EXTERNAL_RISK_OUTCOMES).tag("result", "unmatched")
                .tag("failureCategory", "none").counter()).isNotNull();
        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .RULE_ANALYSIS_OUTCOMES).tag("result", "failed")
                .tag("failureCategory", "unknown").counter()).isNotNull();
    }

    @Test
    void preservesSixExternalAndAllRuleClientFailureCategoryNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTransactionProcessingMetricsRecorder recorder =
                new MicrometerTransactionProcessingMetricsRecorder(registry);

        for (ExternalRiskFailureCategory category
                : ExternalRiskFailureCategory.values()) {
            recorder.recordExternalRisk(
                    TransactionProcessingMetricsRecorder.ExternalRiskResult
                            .FAILED,
                    TransactionProcessingMetricsRecorder.FailureCategory
                            .fromExternalRisk(category),
                    Duration.ZERO
            );
        }
        Set<String> ruleClientCategories = Set.of(
                "AI_SERVICE_REQUEST_CONTRACT_ERROR",
                "AI_SERVICE_PAYLOAD_TOO_LARGE",
                "AI_SERVICE_RULE_CONTRACT_ERROR",
                "AI_SERVICE_CAPABILITY_MISMATCH",
                "AI_SERVICE_INTERNAL_ERROR",
                "AI_SERVICE_CONNECT_TIMEOUT",
                "AI_SERVICE_RESPONSE_TIMEOUT",
                "AI_SERVICE_UNAVAILABLE",
                "AI_SERVICE_INVALID_RESPONSE"
        );
        ruleClientCategories.forEach(category -> recorder.recordRuleAnalysis(
                TransactionProcessingMetricsRecorder.RuleResult.FAILED,
                null,
                TransactionProcessingMetricsRecorder.FailureCategory
                        .fromRule(category),
                Duration.ZERO
        ));

        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .EXTERNAL_RISK_OUTCOMES).counters())
                .extracting(counter -> counter.getId()
                        .getTag("failureCategory"))
                .containsExactlyInAnyOrder(
                        "TIMEOUT", "UNAVAILABLE", "INVALID_REQUEST",
                        "UNSUPPORTED_CAPABILITY", "INVALID_RESPONSE",
                        "TRANSFORMATION_ERROR"
                );
        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .RULE_ANALYSIS_OUTCOMES).counters())
                .extracting(counter -> counter.getId()
                        .getTag("failureCategory"))
                .containsExactlyInAnyOrderElementsOf(ruleClientCategories);
    }

    @Test
    void nullExternalRiskCommandKeepsTypedFailureAndRecordsInvalidRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTransactionProcessingMetricsRecorder recorder =
                new MicrometerTransactionProcessingMetricsRecorder(registry);
        ExternalRiskPolicyService service = new ExternalRiskPolicyService(
                request -> {
                    throw new AssertionError("provider must not be called");
                },
                java.time.Clock.systemUTC(),
                recorder
        );

        assertThatThrownBy(() -> service.lookup(null))
                .isInstanceOf(ExternalRiskLookupException.class)
                .extracting(throwable ->
                        ((ExternalRiskLookupException) throwable).category())
                .isEqualTo(ExternalRiskFailureCategory.INVALID_REQUEST);
        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .EXTERNAL_RISK_OUTCOMES).tag("result", "failed")
                .tag("failureCategory", "INVALID_REQUEST").counter())
                .isNotNull();
    }

    @Test
    void skipsNegativeDurationsAndAbsorbsRegistryFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTransactionProcessingMetricsRecorder recorder =
                new MicrometerTransactionProcessingMetricsRecorder(registry);
        recorder.recordTransactionTerminal(
                TransactionProcessingStatus.APPROVED,
                RiskLevel.LOW,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                Duration.ofNanos(-1)
        );

        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTION_OUTCOMES).counter()).isNotNull();
        assertThat(registry.find(MicrometerTransactionProcessingMetricsRecorder
                .TRANSACTION_PROCESSING_DURATION).timer()).isNull();

        MicrometerTransactionProcessingMetricsRecorder unavailable =
                new MicrometerTransactionProcessingMetricsRecorder(
                        (io.micrometer.core.instrument.MeterRegistry) null
                );
        assertThatCode(() -> unavailable.recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome.ACCEPTED
        )).doesNotThrowAnyException();
    }

    @Test
    void filterFinishesPublicPostExactlyOnceAndIgnoresOtherEndpoints()
            throws Exception {
        TransactionProcessingMetricsRecorder recorder = mock(
                TransactionProcessingMetricsRecorder.class
        );
        TransactionIntakeMetricsFilter filter =
                new TransactionIntakeMetricsFilter(recorder);
        MockHttpServletRequest intake = new MockHttpServletRequest(
                "POST", "/api/v1/transactions"
        );
        intake.setServletPath("/api/v1/transactions");

        filter.doFilter(
                intake,
                new MockHttpServletResponse(),
                (request, response) -> {
                    TransactionIntakeMetricsFilter.markOutcome(
                            (MockHttpServletRequest) request,
                            TransactionProcessingMetricsRecorder.IntakeOutcome
                                    .IDEMPOTENT_REPLAY
                    );
                    TransactionIntakeMetricsFilter.markOutcome(
                            (MockHttpServletRequest) request,
                            TransactionProcessingMetricsRecorder.IntakeOutcome
                                    .IDEMPOTENT_REPLAY
                    );
                    TransactionIntakeMetricsFilter.markDuplicate(
                            (MockHttpServletRequest) request,
                            TransactionProcessingMetricsRecorder.DuplicateResult
                                    .COMPLETED
                    );
                }
        );

        verify(recorder, times(1)).recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome
                        .IDEMPOTENT_REPLAY
        );
        verify(recorder, times(1)).recordDuplicateRequest(
                TransactionProcessingMetricsRecorder.DuplicateResult.COMPLETED
        );

        TransactionProcessingMetricsRecorder ignored = mock(
                TransactionProcessingMetricsRecorder.class
        );
        TransactionIntakeMetricsFilter ignoredFilter =
                new TransactionIntakeMetricsFilter(ignored);
        MockHttpServletRequest health = new MockHttpServletRequest(
                "GET", "/api/v1/health"
        );
        health.setServletPath("/api/v1/health");
        ignoredFilter.doFilter(
                health,
                new MockHttpServletResponse(),
                (request, response) -> {
                }
        );
        verifyNoInteractions(ignored);
    }

    @Test
    void filterAbsorbsRecorderFailureWithoutChangingResponse()
            throws Exception {
        TransactionProcessingMetricsRecorder recorder = mock(
                TransactionProcessingMetricsRecorder.class
        );
        doThrow(new IllegalStateException("meter unavailable"))
                .when(recorder).recordIntakeOutcome(
                        TransactionProcessingMetricsRecorder.IntakeOutcome
                                .ACCEPTED
                );
        TransactionIntakeMetricsFilter filter =
                new TransactionIntakeMetricsFilter(recorder);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/transactions"
        );
        request.setServletPath("/api/v1/transactions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    TransactionIntakeMetricsFilter.markOutcome(
                            (MockHttpServletRequest) servletRequest,
                            TransactionProcessingMetricsRecorder.IntakeOutcome
                                    .ACCEPTED
                    );
                    ((MockHttpServletResponse) servletResponse).setStatus(201);
                }
        )).doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(201);
    }

    private void assertCounter(
            SimpleMeterRegistry registry,
            String name,
            String result
    ) {
        Counter counter = result == null
                ? registry.find(name).counter()
                : registry.find(name).tag("result", result).counter();
        assertThat(counter).isNotNull();
        Meter.Id id = counter.getId();
        assertThat(id.getType()).isEqualTo(Meter.Type.COUNTER);
        assertThat(id.getDescription()).isNotBlank();
        assertThat(id.getBaseUnit()).isNull();
    }

    private void assertScrapedCounter(
            String scrape,
            String name,
            String requiredTag
    ) {
        assertThat(scrape).contains(
                "# HELP " + name + " ",
                "# TYPE " + name + " counter"
        );
        assertThat(scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .toList())
                .singleElement()
                .asString()
                .contains(
                        "service=\"spring-backend\"",
                        requiredTag
                );
    }

    private void assertScrapedTimer(String scrape, String name) {
        assertThat(scrape).contains(
                "# HELP " + name + " ",
                "# TYPE " + name + " summary"
        );
        assertThat(scrape.lines())
                .anyMatch(line -> line.startsWith(name + "_count{")
                        && line.contains("service=\"spring-backend\""))
                .anyMatch(line -> line.startsWith(name + "_sum{")
                        && line.contains("service=\"spring-backend\""));
    }

    private void assertTimer(SimpleMeterRegistry registry, String name) {
        Timer timer = registry.find(name).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.getId().getType()).isEqualTo(Meter.Type.TIMER);
        assertThat(timer.getId().getDescription()).isNotBlank();
        assertThat(timer.count()).isEqualTo(1);
    }
}
