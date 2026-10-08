package com.aifds.backend.rule.service;

import com.aifds.backend.rule.contract.RuleV1DefaultRuleSetDefinition;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Explicit local-only, all-or-nothing publication of the four immutable v2 versions. */
@Service
public class RuleV2LocalPublicationService {
    private final RuleVersionRepository versions;
    private final Clock clock;

    public RuleV2LocalPublicationService(RuleVersionRepository versions, Clock clock) {
        this.versions = versions;
        this.clock = clock;
    }

    @Transactional
    public List<RuleVersion> publish(Instant effectiveFrom) {
        if (effectiveFrom == null || effectiveFrom.getNano() % 1_000 != 0
                || !effectiveFrom.isAfter(clock.instant())) {
            throw new IllegalArgumentException("v2 effectiveFrom must be a future microsecond timestamp");
        }
        var definitions = RuleV1DefaultRuleSetDefinition.rules();
        var locked = versions.findAllByRuleVersionIdInForUpdate(
                definitions.stream().map(RuleV1DefaultRuleSetDefinition.DefaultRule::ruleVersionId).toList());
        if (locked.size() != 4) {
            throw new IllegalStateException("Complete published v1 set is required");
        }
        List<RuleVersion> created = new ArrayList<>();
        Instant publishedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for (var definition : definitions) {
            RuleVersion previous = locked.stream()
                    .filter(value -> value.getRuleVersionId().equals(definition.ruleVersionId()))
                    .findFirst().orElseThrow();
            if (previous.getStatus() != RuleVersionStatus.PUBLISHED
                    || previous.getVersionNumber() != 1 || previous.getEffectiveTo() != null
                    || !previous.getFraudRule().getFraudRuleId().equals(definition.fraudRuleId())) {
                throw new IllegalStateException("v1 publication boundary does not match");
            }
            RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                    previous.getFraudRule().getRuleCode(), previous.getReasonCode(),
                    previous.getWeight(), previous.getConditionDefinition());
            if (versions.findByFraudRule_RuleCodeAndVersionNumber(
                    previous.getFraudRule().getRuleCode(), 2).isPresent()) {
                throw new IllegalStateException("v2 version already exists");
            }
            if (!effectiveFrom.isAfter(previous.getEffectiveFrom())) {
                throw new IllegalArgumentException("v2 must start after v1");
            }
            previous.closeEffectivePeriod(effectiveFrom);
            RuleVersion next = RuleVersion.draft(previous.getFraudRule(), 2,
                    previous.getReasonCode(), previous.getWeight(),
                    previous.getConditionDefinition(), effectiveFrom, null);
            next.publish(publishedAt);
            created.add(next);
        }
        versions.saveAllAndFlush(locked);
        return versions.saveAllAndFlush(created);
    }
}
