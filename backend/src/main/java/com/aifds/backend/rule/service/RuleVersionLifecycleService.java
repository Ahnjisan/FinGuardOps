package com.aifds.backend.rule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.exception.RuleVersionPeriodOverlapException;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class RuleVersionLifecycleService {

    private final FraudRuleRepository fraudRuleRepository;
    private final RuleVersionRepository ruleVersionRepository;

    public RuleVersionLifecycleService(
            FraudRuleRepository fraudRuleRepository,
            RuleVersionRepository ruleVersionRepository
    ) {
        this.fraudRuleRepository = fraudRuleRepository;
        this.ruleVersionRepository = ruleVersionRepository;
    }

    @Transactional
    public void publish(UUID ruleVersionId, Instant publishedAt) {
        RuleVersion version = versionForUpdate(ruleVersionId);
        FraudRule rule = fraudRuleRepository
                .findByFraudRuleIdForUpdate(
                        version.getFraudRule().getFraudRuleId()
                )
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Fraud rule does not exist"
                        )
                );
        if (version.getStatus() != RuleVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Rule version status must be DRAFT"
            );
        }
        if (version.getEffectiveFrom() == null) {
            throw new IllegalStateException(
                    "Published rule versions require effectiveFrom"
            );
        }
        long overlaps = version.getEffectiveTo() == null
                ? ruleVersionRepository
                .countOverlappingPublishedVersionsInOpenEndedPeriod(
                        rule.getId(),
                        version.getEffectiveFrom()
                )
                : ruleVersionRepository
                .countOverlappingPublishedVersionsInBoundedPeriod(
                        rule.getId(),
                        version.getEffectiveFrom(),
                        version.getEffectiveTo()
                );
        if (overlaps > 0) {
            throw new RuleVersionPeriodOverlapException();
        }
        version.publish(publishedAt);
        ruleVersionRepository.saveAndFlush(version);
    }

    @Transactional
    public void updateDraft(
            UUID ruleVersionId,
            String reasonCode,
            int weight,
            JsonNode conditionDefinition,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        RuleVersion version = versionForUpdate(ruleVersionId);
        version.updateDraft(
                reasonCode,
                weight,
                conditionDefinition,
                effectiveFrom,
                effectiveTo
        );
        ruleVersionRepository.saveAndFlush(version);
    }

    @Transactional
    public void withdraw(UUID ruleVersionId) {
        RuleVersion version = versionForUpdate(ruleVersionId);
        version.withdraw();
        ruleVersionRepository.saveAndFlush(version);
    }

    @Transactional
    public void closeEffectivePeriod(
            UUID ruleVersionId,
            Instant effectiveTo
    ) {
        RuleVersion version = versionForUpdate(ruleVersionId);
        version.closeEffectivePeriod(effectiveTo);
        ruleVersionRepository.saveAndFlush(version);
    }

    private RuleVersion versionForUpdate(UUID ruleVersionId) {
        return ruleVersionRepository
                .findByRuleVersionIdForUpdate(ruleVersionId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Rule version does not exist"
                        )
                );
    }
}
