package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

@Component
public final class RuleAnalysisResponseMapper {

    MappedRuleAnalysisResult map(RuleAnalysisResponse response) {
        RuleAnalysisResponse source = Objects.requireNonNull(
                response,
                "response must not be null"
        );
        List<RuleEvidenceResponse> evidence = source.analysis().evidence();
        List<RuleEvidenceDraft> drafts = IntStream.range(0, evidence.size())
                .mapToObj(index -> toDraft(evidence.get(index), index))
                .toList();
        return new MappedRuleAnalysisResult(
                source.analysis().scoringResult().riskScore(),
                toPersistenceRiskLevel(
                        source.analysis().scoringResult().riskLevel()
                ),
                drafts
        );
    }

    private RuleEvidenceDraft toDraft(
            RuleEvidenceResponse evidence,
            int sortOrder
    ) {
        return new RuleEvidenceDraft(
                evidence.ruleVersionId(),
                RuleV1ContractRegistry.displayDescriptionFor(
                        evidence.reasonCode()
                ),
                evidence.observationSummary().deepCopy(),
                evidence.evidenceOccurredAt(),
                sortOrder
        );
    }

    private RiskLevel toPersistenceRiskLevel(RuleRiskLevel riskLevel) {
        return switch (Objects.requireNonNull(
                riskLevel,
                "riskLevel must not be null"
        )) {
            case LOW -> RiskLevel.LOW;
            case MEDIUM -> RiskLevel.MEDIUM;
            case HIGH -> RiskLevel.HIGH;
            case CRITICAL -> RiskLevel.CRITICAL;
        };
    }

    record MappedRuleAnalysisResult(
            int riskScore,
            RiskLevel riskLevel,
            List<RuleEvidenceDraft> evidenceDrafts
    ) {

        MappedRuleAnalysisResult {
            if (riskScore < 0 || riskScore > 100) {
                throw new IllegalArgumentException(
                        "riskScore must be between 0 and 100"
                );
            }
            Objects.requireNonNull(riskLevel, "riskLevel must not be null");
            evidenceDrafts = List.copyOf(Objects.requireNonNull(
                    evidenceDrafts,
                    "evidenceDrafts must not be null"
            ));
        }
    }
}
