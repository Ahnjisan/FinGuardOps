package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.rule.client.RuleAnalysisClientException;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RuleAnalysisOrchestrationService {

    static final String HTTP_CALL_FAILED =
            "RULE_ANALYSIS_HTTP_CALL_FAILED";
    static final String RESPONSE_MAPPING_FAILED =
            "RULE_ANALYSIS_RESPONSE_MAPPING_FAILED";
    static final String ADOPTION_FAILED =
            "RULE_ANALYSIS_ADOPTION_FAILED";
    static final String TRANSACTION_BOUNDARY_VIOLATION =
            "RULE_ANALYSIS_TRANSACTION_BOUNDARY_VIOLATION";
    static final String START_FAILED = "RULE_ANALYSIS_START_FAILED";

    private final RuleAnalysisPersistenceService persistenceService;
    private final RuleAnalysisHttpClient httpClient;
    private final RuleAnalysisResponseMapper responseMapper;
    private final Clock clock;
    private final TransactionProcessingMetricsRecorder metricsRecorder;

    @Autowired
    public RuleAnalysisOrchestrationService(
            RuleAnalysisPersistenceService persistenceService,
            RuleAnalysisHttpClient httpClient,
            RuleAnalysisResponseMapper responseMapper,
            Clock clock,
            TransactionProcessingMetricsRecorder metricsRecorder
    ) {
        this.persistenceService = persistenceService;
        this.httpClient = httpClient;
        this.responseMapper = responseMapper;
        this.clock = clock;
        this.metricsRecorder = metricsRecorder == null
                ? TransactionProcessingMetricsRecorder.noop()
                : metricsRecorder;
    }

    public RuleAnalysisOrchestrationService(
            RuleAnalysisPersistenceService persistenceService,
            RuleAnalysisHttpClient httpClient,
            RuleAnalysisResponseMapper responseMapper,
            Clock clock
    ) {
        this(
                persistenceService,
                httpClient,
                responseMapper,
                clock,
                TransactionProcessingMetricsRecorder.noop()
        );
    }

    public CompletedRuleAnalysis analyze(
            UUID transactionId,
            String analysisTraceId
    ) {
        long metricStartedAt = System.nanoTime();
        try {
            requireNoActiveTransaction();
        } catch (RuntimeException original) {
            recordRuleFailure(TRANSACTION_BOUNDARY_VIOLATION, metricStartedAt);
            throw original;
        }
        final RuleV1ContractRegistry.RuleAnalysisMetadata metadata;
        final StartedRuleAnalysisExecution execution;
        try {
            metadata = RuleV1ContractRegistry.ruleAnalysisMetadata();
            execution = persistenceService.startAnalysis(
                    transactionId,
                    metadata.scoringPolicyVersion(),
                    metadata.featureVersion(),
                    metadata.modelVersion(),
                    analysisTraceId,
                    clock.instant()
            );
        } catch (RuntimeException original) {
            recordRuleFailure(START_FAILED, metricStartedAt);
            throw original;
        }
        StartedRuleAnalysis started = execution.startedAnalysis();

        return executeStartedAnalysis(
                started,
                () -> httpClient.analyze(
                        execution.request(),
                        started.analysisTraceId()
                ),
                metricStartedAt
        );
    }

    public CompletedRuleAnalysis analyzeV2(
            UUID transactionId,
            ExternalRiskSnapshot externalRiskSnapshot,
            String analysisTraceId
    ) {
        long metricStartedAt = System.nanoTime();
        try {
            requireNoActiveTransaction();
        } catch (RuntimeException original) {
            recordRuleFailure(TRANSACTION_BOUNDARY_VIOLATION, metricStartedAt);
            throw original;
        }
        final RuleV1ContractRegistry.RuleAnalysisMetadata metadata;
        final StartedRuleAnalysisV2Execution execution;
        try {
            metadata = RuleV1ContractRegistry.ruleAnalysisMetadata();
            execution = persistenceService.startAnalysisV2(
                    transactionId,
                    externalRiskSnapshot,
                    metadata.scoringPolicyVersion(),
                    metadata.featureVersion(),
                    metadata.modelVersion(),
                    analysisTraceId,
                    clock.instant()
            );
        } catch (RuntimeException original) {
            recordRuleFailure(START_FAILED, metricStartedAt);
            throw original;
        }
        StartedRuleAnalysis started = execution.startedAnalysis();

        return executeStartedAnalysis(
                started,
                () -> httpClient.analyzeV2(
                        execution.request(),
                        started.analysisTraceId()
                ),
                metricStartedAt
        );
    }

    private CompletedRuleAnalysis executeStartedAnalysis(
            StartedRuleAnalysis started,
            Supplier<RuleAnalysisResponse> clientCall,
            long metricStartedAt
    ) {
        try {
            requireNoActiveTransaction();
        } catch (RuntimeException original) {
            throw recordFailureAndMetric(
                    started,
                    TRANSACTION_BOUNDARY_VIOLATION,
                    original,
                    metricStartedAt
            );
        }

        RuleAnalysisResponse response;
        try {
            response = clientCall.get();
        } catch (RuleAnalysisClientException original) {
            throw recordFailureAndMetric(
                    started,
                    original.category().name(),
                    original,
                    metricStartedAt
            );
        } catch (RuntimeException original) {
            throw recordFailureAndMetric(
                    started,
                    HTTP_CALL_FAILED,
                    original,
                    metricStartedAt
            );
        }

        RuleAnalysisResponseMapper.MappedRuleAnalysisResult mapped;
        try {
            mapped = responseMapper.map(response);
        } catch (RuntimeException original) {
            throw recordFailureAndMetric(
                    started,
                    RESPONSE_MAPPING_FAILED,
                    original,
                    metricStartedAt
            );
        }

        try {
            persistenceService.completeAndAdopt(
                    started,
                    mapped.riskScore(),
                    mapped.riskLevel(),
                    clock.instant(),
                    mapped.evidenceDrafts()
            );
        } catch (RuntimeException original) {
            throw recordFailureAndMetric(
                    started,
                    ADOPTION_FAILED,
                    original,
                    metricStartedAt
            );
        }

        recordRuleSuccess(mapped.riskLevel(), metricStartedAt);

        return new CompletedRuleAnalysis(
                started.transactionId(),
                started.detectionResultId(),
                started.detectionResultVersion(),
                mapped.riskScore(),
                mapped.riskLevel()
        );
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Rule analysis orchestration requires no active transaction"
            );
        }
    }

    private <T extends RuntimeException> T recordFailure(
            StartedRuleAnalysis started,
            String failureCode,
            T original
    ) {
        try {
            persistenceService.failAnalysis(
                    started,
                    failureCode,
                    clock.instant()
            );
        } catch (RuntimeException recordingFailure) {
            if (recordingFailure != original) {
                original.addSuppressed(recordingFailure);
            }
        }
        return original;
    }

    private <T extends RuntimeException> T recordFailureAndMetric(
            StartedRuleAnalysis started,
            String failureCode,
            T original,
            long metricStartedAt
    ) {
        T recorded = recordFailure(started, failureCode, original);
        recordRuleFailure(failureCode, metricStartedAt);
        return recorded;
    }

    private void recordRuleSuccess(
            RiskLevel riskLevel,
            long metricStartedAt
    ) {
        recordMetric(
                TransactionProcessingMetricsRecorder.RuleResult.COMPLETED,
                riskLevel,
                TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                metricStartedAt
        );
    }

    private void recordRuleFailure(String failureCode, long metricStartedAt) {
        recordMetric(
                TransactionProcessingMetricsRecorder.RuleResult.FAILED,
                null,
                TransactionProcessingMetricsRecorder.FailureCategory
                        .fromRule(failureCode),
                metricStartedAt
        );
    }

    private void recordMetric(
            TransactionProcessingMetricsRecorder.RuleResult result,
            RiskLevel riskLevel,
            TransactionProcessingMetricsRecorder.FailureCategory category,
            long metricStartedAt
    ) {
        try {
            long elapsed = System.nanoTime() - metricStartedAt;
            metricsRecorder.recordRuleAnalysis(
                    result,
                    riskLevel,
                    category,
                    Duration.ofNanos(Math.max(0L, elapsed))
            );
        } catch (Throwable ignored) {
            // Metrics cannot replace orchestration or persistence results.
        }
    }
}
