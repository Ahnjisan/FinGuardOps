package com.aifds.backend.externalrisk.service;

import com.aifds.backend.detection.service.CompletedRuleAnalysis;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.domain.ExternalRiskContracts;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

public final class ExternalRiskRuleAnalysisCoordinator {

    private final ExternalRiskLookupCommandReader commandReader;
    private final ExternalRiskPolicyService policyService;
    private final RuleAnalysisOrchestrationService orchestrationService;

    public ExternalRiskRuleAnalysisCoordinator(
            ExternalRiskLookupCommandReader commandReader,
            ExternalRiskPolicyService policyService,
            RuleAnalysisOrchestrationService orchestrationService
    ) {
        this.commandReader = Objects.requireNonNull(
                commandReader,
                "commandReader must not be null"
        );
        this.policyService = Objects.requireNonNull(
                policyService,
                "policyService must not be null"
        );
        this.orchestrationService = Objects.requireNonNull(
                orchestrationService,
                "orchestrationService must not be null"
        );
    }

    public CompletedRuleAnalysis analyzeWithExternalRisk(
            UUID transactionId,
            String traceId
    ) {
        ExternalRiskSnapshot snapshot = lookupExternalRisk(
                transactionId,
                traceId
        );
        return analyzeWithExternalRiskSnapshot(
                transactionId,
                traceId,
                snapshot
        );
    }

    public ExternalRiskSnapshot lookupExternalRisk(
            UUID transactionId,
            String traceId
    ) {
        validateInput(transactionId, traceId);
        requireNoActiveTransaction();

        ExternalRiskLookupCommand command = commandReader.read(
                transactionId,
                traceId
        );
        requireNoActiveTransaction();

        ExternalRiskSnapshot snapshot = policyService.lookup(command);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "External Risk Policy returned no snapshot"
            );
        }
        requireNoActiveTransaction();

        return snapshot;
    }

    public CompletedRuleAnalysis analyzeWithExternalRiskSnapshot(
            UUID transactionId,
            String traceId,
            ExternalRiskSnapshot snapshot
    ) {
        validateInput(transactionId, traceId);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "External Risk Policy returned no snapshot"
            );
        }
        if (!transactionId.equals(snapshot.transactionId())) {
            throw new IllegalArgumentException(
                    "External Risk snapshot transactionId must match input"
            );
        }
        requireNoActiveTransaction();

        return orchestrationService.analyzeV2(
                transactionId,
                snapshot,
                traceId
        );
    }

    private void validateInput(UUID transactionId, String traceId) {
        if (!ExternalRiskContracts.isUuidV4(transactionId)) {
            throw new IllegalArgumentException(
                    "transactionId must be an RFC 4122 UUID v4"
            );
        }
        if (!ExternalRiskContracts.isTraceId(traceId)) {
            throw new IllegalArgumentException(
                    "traceId has an invalid format"
            );
        }
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "External Risk Rule analysis requires no active transaction"
            );
        }
    }
}
