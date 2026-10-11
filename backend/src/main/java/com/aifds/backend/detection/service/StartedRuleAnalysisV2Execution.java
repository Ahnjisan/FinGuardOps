package com.aifds.backend.detection.service;

import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.scenario.ScenarioFacts;
import com.aifds.backend.scenario.ScenarioAggregationFacts;

import java.util.Objects;

public record StartedRuleAnalysisV2Execution(
        StartedRuleAnalysis startedAnalysis,
        RuleAnalysisRequestV2 request,
        boolean priorApprovedRecipientTransfer,
        ScenarioFacts scenarioFacts,
        ScenarioAggregationFacts aggregationFacts
) {

    public StartedRuleAnalysisV2Execution(
            StartedRuleAnalysis startedAnalysis, RuleAnalysisRequestV2 request) {
        this(startedAnalysis, request, false, null, null);
    }

    public StartedRuleAnalysisV2Execution(StartedRuleAnalysis startedAnalysis,
                                          RuleAnalysisRequestV2 request,
                                          boolean priorApprovedRecipientTransfer) {
        this(startedAnalysis, request, priorApprovedRecipientTransfer, null, null);
    }

    public StartedRuleAnalysisV2Execution(StartedRuleAnalysis startedAnalysis,
            RuleAnalysisRequestV2 request, boolean priorApprovedRecipientTransfer,
            ScenarioFacts scenarioFacts) {
        this(startedAnalysis, request, priorApprovedRecipientTransfer, scenarioFacts, null);
    }

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
        var calculator = new CanonicalRuleSetVersionCalculator();
        int policy = RulePolicyVersion.from(request.ruleVersions());
        String requestRuleSetVersion = policy == 5
                ? calculator.calculateV5(request.ruleVersions())
                : policy == 4 ? calculator.calculateV4(request.ruleVersions())
                : calculator.calculate(
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
        if (policy == 5 && (scenarioFacts == null || aggregationFacts == null)) {
            throw new IllegalArgumentException("Rule v5 facts are incomplete");
        }
    }
}
