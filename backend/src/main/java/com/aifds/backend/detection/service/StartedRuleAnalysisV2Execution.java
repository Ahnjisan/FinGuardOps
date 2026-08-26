package com.aifds.backend.detection.service;

import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;

import java.util.Objects;

public record StartedRuleAnalysisV2Execution(
        StartedRuleAnalysis startedAnalysis,
        RuleAnalysisRequestV2 request
) {

    public StartedRuleAnalysisV2Execution {
        Objects.requireNonNull(
                startedAnalysis,
                "startedAnalysis must not be null"
        );
        Objects.requireNonNull(request, "request must not be null");
        if (!startedAnalysis.transactionId().equals(
                request.transaction().transactionId()
        )) {
            throw new IllegalArgumentException(
                    "Started analysis transaction does not match request"
            );
        }
        if (!startedAnalysis.evaluationCutoffAt().equals(
                request.evaluationCutoffAt()
        )) {
            throw new IllegalArgumentException(
                    "Started analysis cutoff does not match request"
            );
        }
        String requestRuleSetVersion =
                new CanonicalRuleSetVersionCalculator().calculate(
                        request.ruleVersions().stream()
                                .map(rule -> new RuleV1ExecutionPlanRegistry
                                        .RuleVersionIdentity(
                                        rule.fraudRuleId(),
                                        rule.ruleVersionId(),
                                        rule.ruleCode(),
                                        rule.versionNumber()
                                ))
                                .toList()
                );
        if (!startedAnalysis.ruleSetVersion().equals(requestRuleSetVersion)) {
            throw new IllegalArgumentException(
                    "Started analysis ruleSetVersion does not match request"
            );
        }
    }
}
