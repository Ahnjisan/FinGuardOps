package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.client.AiReportHttpClient;
import com.aifds.backend.aireport.config.AiReportProperties;
import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.entity.AiReportExecution;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.repository.AiReportRepository;
import com.aifds.backend.aireport.repository.AiReportRequestRepository;
import com.aifds.backend.aireport.repository.ProviderCallAttemptRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import com.aifds.backend.observability.LocalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AiReportWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiReportWorker.class);
    private final AiReportProperties.Values properties;
    private final ObjectProvider<PlatformTransactionManager> transactionManagers;
    private final ObjectProvider<JdbcTemplate> jdbcTemplates;
    private final ObjectProvider<AiReportExecutionRepository> executionBeans;
    private final ObjectProvider<AiReportRequestRepository> requestBeans;
    private final ObjectProvider<AiReportRepository> reportBeans;
    private final ObjectProvider<ProviderCallAttemptRepository> attemptBeans;
    private final ObjectProvider<FraudCaseRepository> caseBeans;
    private final ObjectProvider<AiReportInputProjection> projectionBeans;
    private final ObjectProvider<AiReportHttpClient> clientBeans;
    private final ObjectProvider<AiReportKafkaMetrics> metricsBeans;

    public AiReportWorker(ObjectProvider<PlatformTransactionManager> transactionManagers,
                          ObjectProvider<JdbcTemplate> jdbcTemplates,
                          AiReportProperties.Values properties,
                          ObjectProvider<AiReportExecutionRepository> executionBeans,
                          ObjectProvider<AiReportRequestRepository> requestBeans,
                          ObjectProvider<AiReportRepository> reportBeans,
                          ObjectProvider<ProviderCallAttemptRepository> attemptBeans,
                          ObjectProvider<FraudCaseRepository> caseBeans,
                          ObjectProvider<AiReportInputProjection> projectionBeans,
                          ObjectProvider<AiReportHttpClient> clientBeans,
                          ObjectProvider<AiReportKafkaMetrics> metricsBeans) {
        this.transactionManagers = transactionManagers;
        this.jdbcTemplates = jdbcTemplates;
        this.properties = properties;
        this.executionBeans = executionBeans;
        this.requestBeans = requestBeans;
        this.reportBeans = reportBeans;
        this.attemptBeans = attemptBeans;
        this.caseBeans = caseBeans;
        this.projectionBeans = projectionBeans;
        this.clientBeans = clientBeans;
        this.metricsBeans = metricsBeans;
    }

    @Scheduled(fixedDelayString = "${finguardops.ai-report.poll-interval-ms:1000}")
    public void tick() {
        run(null);
    }

    public StartResult runExecution(UUID executionId) {
        return run(java.util.Objects.requireNonNull(executionId));
    }

    private StartResult run(UUID executionId) {
        var manager = transactionManagers.getIfAvailable();
        if (manager == null || jdbcTemplates.getIfAvailable() == null) return StartResult.UNAVAILABLE;
        var executions = executionBeans.getIfAvailable();
        var requests = requestBeans.getIfAvailable();
        var reports = reportBeans.getIfAvailable();
        var attempts = attemptBeans.getIfAvailable();
        var cases = caseBeans.getIfAvailable();
        var projection = projectionBeans.getIfAvailable();
        var client = clientBeans.getIfAvailable();
        if (executions == null || requests == null || reports == null || attempts == null
                || cases == null || projection == null || client == null) return StartResult.UNAVAILABLE;
        var transactions = new TransactionTemplate(manager);
        transactions.executeWithoutResult(ignored -> {
            executions.expireLeases();
            requests.failExpired();
        });
        Optional<AiReportExecution> claimed = transactions.execute(ignored -> {
            Optional<AiReportExecution> next = executionId == null
                    ? executions.claim(properties.leaseSeconds())
                    : executions.claim(executionId, properties.leaseSeconds());
            next.ifPresent(row -> requests.generating(row.id()));
            return next;
        });
        if (claimed == null || claimed.isEmpty()) {
            return StartResult.NOT_CLAIMED;
        }
        AiReportKafkaMetrics metrics = metricsBeans.getIfAvailable();
        if (metrics != null) metrics.started(executionId == null ? "polling" : "kafka");
        AiReportExecution row = claimed.get();
        String source = executionId == null ? "polling" : "kafka";
        try (LocalTrace traceScope = LocalTrace.execution("ai-report.worker", row.executionId())) {
            LOGGER.info("event=ai_report_worker_started executionId={} source={} otelTraceId={}",
                    row.executionId(), source, LocalTrace.currentTraceId());
            AiReportDtos.GenerationRequest input;
            try {
                var projected = transactions.execute(ignored -> {
                    var fraudCase = cases.findById(row.casePk()).orElseThrow();
                    return projection.project(fraudCase, row.detectionResultVersion(),
                            requests.initiators(row.id()).get(0).traceId());
                });
                if (projected == null || projected.detectionPk() != row.detectionPk()) {
                    fail(transactions, executions, requests, row, "REPORT_INPUT_CHANGED");
                    return StartResult.STARTED;
                }
                input = projected.request();
            } catch (com.aifds.backend.aireport.exception.AiReportException
                     | java.util.NoSuchElementException exception) {
                fail(transactions, executions, requests, row, "REPORT_INPUT_CHANGED");
                return StartResult.STARTED;
            }
            AiReportDtos.GenerationResult generated;
            try {
                generated = client.generate(input);
            } catch (AiReportHttpClient.GenerationFailure exception) {
                fail(transactions, executions, requests, row, exception.code());
                return StartResult.STARTED;
            }
            try {
                validate(generated, row, input);
            } catch (RuntimeException exception) {
                fail(transactions, executions, requests, row, "FASTAPI_RESPONSE_INVALID");
                return StartResult.STARTED;
            }
            Boolean persisted = transactions.execute(ignored -> {
                if (!executions.stillGenerating(row.id())) return false;
                for (int index = 0; index < generated.attempts().size(); index++) {
                    attempts.insert(row.id(), index + 1, generated.attempts().get(index));
                }
                AiReportStatus status = AiReportStatus.valueOf(generated.status());
                if (status == AiReportStatus.FAILED) {
                    executions.complete(row.id(), status, generated.failureCode(),
                            generated.fallbackTriggerCode());
                    requests.fail(row.id());
                    return true;
                }
                String traceId = requests.initiators(row.id()).get(0).traceId();
                var report = reports.insert(row.casePk(), row.id(), row.detectionResultVersion(),
                        row.promptVersion(), row.modelVersion(), generated, traceId);
                executions.complete(row.id(), status, generated.failureCode(),
                        generated.fallbackTriggerCode());
                requests.complete(row.id(), report.id(), status);
                return true;
            });
            LOGGER.info("event=ai_report_worker_finished executionId={} source={} persisted={} reportStatus={} otelTraceId={}",
                    row.executionId(), source, Boolean.TRUE.equals(persisted), generated.status(),
                    LocalTrace.currentTraceId());
            return StartResult.STARTED;
        }
    }

    public enum StartResult { STARTED, NOT_CLAIMED, UNAVAILABLE }

    private void fail(TransactionTemplate transactions, AiReportExecutionRepository executions,
                      AiReportRequestRepository requests, AiReportExecution row, String code) {
        Boolean persisted = transactions.execute(ignored -> {
            if (!executions.stillGenerating(row.id())) return false;
            executions.complete(row.id(), AiReportStatus.FAILED, code, null);
            requests.fail(row.id());
            return true;
        });
        LOGGER.warn("event=ai_report_worker_failed executionId={} code={} persisted={} otelTraceId={}",
                row.executionId(), code, Boolean.TRUE.equals(persisted), LocalTrace.currentTraceId());
    }

    private void validate(AiReportDtos.GenerationResult result, AiReportExecution execution,
                          AiReportDtos.GenerationRequest input) {
        if (result == null || !execution.modelVersion().equals(result.modelVersion())
                || !execution.promptVersion().equals(result.promptVersion())
                || result.attempts() == null || result.attempts().size() > 2) {
            throw new IllegalStateException("AI response contract mismatch");
        }
        for (var attempt : result.attempts()) {
            if (attempt == null || !"OLLAMA_LOCAL".equals(attempt.provider())
                    || !java.util.Set.of("COMPLETED", "TIMEOUT", "CONNECTION_FAILED",
                    "PROVIDER_ERROR", "INVALID_OUTPUT").contains(attempt.outcome())
                    || attempt.latencyMs() < 0
                    || (attempt.inputTokens() != null && attempt.inputTokens() < 0)
                    || (attempt.outputTokens() != null && attempt.outputTokens() < 0)) {
                throw new IllegalStateException("AI attempt contract invalid");
            }
        }
        AiReportStatus status;
        try {
            status = AiReportStatus.valueOf(result.status());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AI response status invalid", exception);
        }
        if (status != AiReportStatus.COMPLETED && status != AiReportStatus.FALLBACK_COMPLETED
                && status != AiReportStatus.FAILED) {
            throw new IllegalStateException("AI response status invalid");
        }
        if (result.fallbackTriggerCode() != null && !java.util.Set.of("LLM_TIMEOUT",
                "LLM_UNAVAILABLE", "LLM_OUTPUT_REJECTED")
                .contains(result.fallbackTriggerCode())) {
            throw new IllegalStateException("AI failure code invalid");
        }
        if ((status == AiReportStatus.COMPLETED && !"LLM".equals(result.source()))
                || (status == AiReportStatus.FALLBACK_COMPLETED
                    && !"TEMPLATE_FALLBACK".equals(result.source()))
                || (status == AiReportStatus.COMPLETED
                    && (result.failureCode() != null || result.fallbackTriggerCode() != null))
                || (status == AiReportStatus.FALLBACK_COMPLETED
                    && (result.failureCode() != null || result.fallbackTriggerCode() == null))
                || (status == AiReportStatus.FAILED
                    && (!"TEMPLATE_FALLBACK_FAILED".equals(result.failureCode())
                        || result.fallbackTriggerCode() == null))) {
            throw new IllegalStateException("AI response source invalid");
        }
        if (status == AiReportStatus.FAILED) {
            if (result.content() != null || result.source() != null) {
                throw new IllegalStateException("Failed report has content");
            }
            return;
        }
        if (result.content() == null || result.content().summary() == null
                || result.content().keyReasons() == null
                || result.content().investigationChecklist() == null) {
            throw new IllegalStateException("Report content missing");
        }
        if (result.content().summary().isBlank() || result.content().summary().length() > 600
                || result.content().keyReasons().isEmpty() || result.content().keyReasons().size() > 20
                || result.content().investigationChecklist().isEmpty()
                || result.content().investigationChecklist().size() > 8
                || result.content().keyReasons().stream().anyMatch(reason -> reason == null
                    || reason.reasonCode() == null || reason.description() == null
                    || reason.description().isBlank() || reason.description().length() > 240)
                || result.content().investigationChecklist().stream().anyMatch(item -> item == null
                    || item.isBlank() || item.length() > 240)) {
            throw new IllegalStateException("Report content invalid");
        }
        var allowed = input.ruleEvidence().stream().map(AiReportDtos.RuleEvidence::reasonCode)
                .collect(java.util.stream.Collectors.toSet());
        var used = result.content().keyReasons().stream().map(AiReportDtos.KeyReason::reasonCode)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.equals(used) || used.size() != result.content().keyReasons().size()) {
            throw new IllegalStateException("Report reasons do not match adopted evidence");
        }
    }
}
