package com.aifds.backend.detection.service;

import com.aifds.backend.rule.client.RuleAnalysisClientException;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.UUID;

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

    private final RuleAnalysisPersistenceService persistenceService;
    private final RuleAnalysisHttpClient httpClient;
    private final RuleAnalysisResponseMapper responseMapper;
    private final Clock clock;

    public RuleAnalysisOrchestrationService(
            RuleAnalysisPersistenceService persistenceService,
            RuleAnalysisHttpClient httpClient,
            RuleAnalysisResponseMapper responseMapper,
            Clock clock
    ) {
        this.persistenceService = persistenceService;
        this.httpClient = httpClient;
        this.responseMapper = responseMapper;
        this.clock = clock;
    }

    public CompletedRuleAnalysis analyze(
            UUID transactionId,
            String analysisTraceId
    ) {
        requireNoActiveTransaction();
        RuleV1ContractRegistry.RuleAnalysisMetadata metadata =
                RuleV1ContractRegistry.ruleAnalysisMetadata();
        StartedRuleAnalysisExecution execution =
                persistenceService.startAnalysis(
                        transactionId,
                        metadata.scoringPolicyVersion(),
                        metadata.featureVersion(),
                        metadata.modelVersion(),
                        analysisTraceId,
                        clock.instant()
                );
        StartedRuleAnalysis started = execution.startedAnalysis();

        try {
            requireNoActiveTransaction();
        } catch (RuntimeException original) {
            throw recordFailure(
                    started,
                    TRANSACTION_BOUNDARY_VIOLATION,
                    original
            );
        }

        RuleAnalysisResponse response;
        try {
            response = httpClient.analyze(
                    execution.request(),
                    started.analysisTraceId()
            );
        } catch (RuleAnalysisClientException original) {
            throw recordFailure(
                    started,
                    original.category().name(),
                    original
            );
        } catch (RuntimeException original) {
            throw recordFailure(started, HTTP_CALL_FAILED, original);
        }

        RuleAnalysisResponseMapper.MappedRuleAnalysisResult mapped;
        try {
            mapped = responseMapper.map(response);
        } catch (RuntimeException original) {
            throw recordFailure(started, RESPONSE_MAPPING_FAILED, original);
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
            throw recordFailure(started, ADOPTION_FAILED, original);
        }

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
}
