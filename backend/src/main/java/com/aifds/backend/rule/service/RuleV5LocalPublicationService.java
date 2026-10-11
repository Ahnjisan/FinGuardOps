package com.aifds.backend.rule.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.scenario.ScenarioAggregationRuleVersionContract;
import com.aifds.backend.scenario.ScenarioRuleVersionContract;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Explicit future publication; all ten existing Rule v4 versions stay immutable. */
@Service
public class RuleV5LocalPublicationService {
    private final RuleVersionRepository versions;
    private final FraudRuleRepository rules;
    private final DatabaseTransactionTimestampProvider databaseTime;

    public RuleV5LocalPublicationService(RuleVersionRepository versions,
                                         FraudRuleRepository rules,
                                         DatabaseTransactionTimestampProvider databaseTime) {
        this.versions = versions;
        this.rules = rules;
        this.databaseTime = databaseTime;
    }

    @Transactional
    public List<RuleVersion> publish(Instant effectiveFrom) {
        Instant now = databaseTime.currentTransactionTimestamp();
        if (effectiveFrom == null || effectiveFrom.getNano() % 1_000 != 0
                || !effectiveFrom.isAfter(now)) {
            throw new IllegalArgumentException("T5 must be a future UTC microsecond timestamp");
        }
        List<RuleVersion> current = versions.findAllExecutableVersions(now);
        if (current.size() != 10 || RulePolicyVersion.from(current.stream()
                .map(this::snapshot).toList()) != 4) {
            throw new IllegalStateException("Complete Rule v4 set is required");
        }
        for (String code : ScenarioAggregationRuleVersionContract.CODES) {
            if (rules.findByRuleCode(code).isPresent()) {
                throw new IllegalStateException("Rule v5 code already exists");
            }
        }
        Instant publishedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<RuleVersion> created = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String code = ScenarioAggregationRuleVersionContract.CODES.get(i);
            FraudRule rule = rules.saveAndFlush(FraudRule.create(code,
                    "SCN-004/005 synthetic Rule " + (i + 11),
                    "Versioned local synthetic transfer aggregation"));
            String signal = i == 0 ? "THREE_RECIPIENTS_TEN_MINUTES"
                    : "THREE_CUSTOMERS_TWENTY_FOUR_HOURS";
            var condition = JsonNodeFactory.instance.objectNode()
                    .put("contractVersion", "SCN004-005-contract-v1")
                    .put("signal", signal);
            RuleVersion version = RuleVersion.draft(rule, 1, code, 25,
                    condition, effectiveFrom, null);
            version.publish(publishedAt);
            created.add(version);
        }
        return versions.saveAllAndFlush(created);
    }

    private RuleVersionSnapshotRequest snapshot(RuleVersion version) {
        return new RuleVersionSnapshotRequest(version.getFraudRule().getFraudRuleId(),
                version.getFraudRule().getRuleCode(), RuleLifecycleStatus.ACTIVE,
                version.getRuleVersionId(), version.getVersionNumber(),
                com.aifds.backend.rule.client.dto.RuleVersionStatus.PUBLISHED,
                version.getReasonCode(), version.getWeight(),
                version.getConditionDefinition(), version.getEffectiveFrom(),
                version.getEffectiveTo());
    }
}
