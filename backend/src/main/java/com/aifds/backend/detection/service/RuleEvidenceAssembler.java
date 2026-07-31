package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RuleEvidenceObservationSummary;
import com.aifds.backend.detection.exception.RuleEvidenceContractViolationException;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.RuleConditionDefinition;
import com.aifds.backend.rule.entity.RuleVersion;

import java.time.Instant;
import java.util.OptionalLong;

public final class RuleEvidenceAssembler {

    private RuleEvidenceAssembler() {
    }

    public static DetectionEvidence assemble(
            DetectionResult result,
            RuleVersion ruleVersion,
            RuleEvidenceDraft draft
    ) {
        String ruleCode = ruleVersion.getFraudRule().getRuleCode();
        String reasonCode = ruleVersion.getReasonCode();
        try {
            RuleV1ContractRegistry.requireCompatible(ruleCode, reasonCode);
        } catch (IllegalArgumentException exception) {
            throw new RuleEvidenceContractViolationException(
                    "RuleVersion code contract is invalid for ruleCode "
                            + ruleCode + " and reasonCode " + reasonCode,
                    exception
            );
        }

        RuleEvidenceObservationSummary summary;
        try {
            summary = RuleEvidenceObservationSummary.from(
                    reasonCode,
                    draft.observationSummary(),
                    result.getEvaluationCutoffAt()
            );
        } catch (IllegalArgumentException exception) {
            throw new RuleEvidenceContractViolationException(
                    "observationSummary violates Rule v1 contract for "
                            + "ruleCode " + ruleCode + " and reasonCode "
                            + reasonCode + ": " + exception.getMessage(),
                    exception
            );
        }
        RuleConditionDefinition conditionDefinition =
                RuleConditionDefinition.from(
                        ruleCode,
                        ruleVersion.getConditionDefinition()
                );
        validateWindowSeconds(
                ruleCode,
                summary.windowSeconds(),
                conditionDefinition.windowSeconds()
        );
        validateEvidenceOccurredAt(
                ruleCode,
                reasonCode,
                draft.evidenceOccurredAt(),
                summary.referenceOccurredAt().orElseGet(
                        () -> result.getFinancialTransaction().getOccurredAt()
                )
        );

        return DetectionEvidence.rule(
                result,
                draft.displayDescription(),
                ruleVersion,
                summary,
                draft.evidenceOccurredAt(),
                draft.sortOrder()
        );
    }

    private static void validateWindowSeconds(
            String ruleCode,
            OptionalLong evidenceWindowSeconds,
            OptionalLong ruleVersionWindowSeconds
    ) {
        if (evidenceWindowSeconds.isPresent()
                != ruleVersionWindowSeconds.isPresent()) {
            throw new RuleEvidenceContractViolationException(
                    "windowSeconds presence does not match RuleVersion for "
                            + "ruleCode " + ruleCode
            );
        }
        if (evidenceWindowSeconds.isPresent()
                && evidenceWindowSeconds.getAsLong()
                != ruleVersionWindowSeconds.getAsLong()) {
            throw new RuleEvidenceContractViolationException(
                    "Evidence windowSeconds does not match RuleVersion for "
                            + "ruleCode " + ruleCode
            );
        }
    }

    private static void validateEvidenceOccurredAt(
            String ruleCode,
            String reasonCode,
            Instant evidenceOccurredAt,
            Instant expectedEvidenceOccurredAt
    ) {
        if (!expectedEvidenceOccurredAt.equals(evidenceOccurredAt)) {
            throw new RuleEvidenceContractViolationException(
                    "evidenceOccurredAt does not match the Rule v1 reference "
                            + "time for ruleCode " + ruleCode
                            + " and reasonCode " + reasonCode
            );
        }
    }
}
