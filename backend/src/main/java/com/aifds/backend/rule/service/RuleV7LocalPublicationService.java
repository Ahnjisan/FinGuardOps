package com.aifds.backend.rule.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.scenario.ScenarioOpenBankingRuleVersionContract;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Future local publication; all fourteen prior Rule versions remain immutable. */
@Service
public class RuleV7LocalPublicationService {
    private final RuleVersionRepository versions;
    private final FraudRuleRepository rules;
    private final DatabaseTransactionTimestampProvider databaseTime;

    public RuleV7LocalPublicationService(RuleVersionRepository versions,
            FraudRuleRepository rules, DatabaseTransactionTimestampProvider databaseTime) {
        this.versions = versions;
        this.rules = rules;
        this.databaseTime = databaseTime;
    }

    @Transactional
    public List<RuleVersion> publish(Instant effectiveFrom) {
        Instant now = databaseTime.currentTransactionTimestamp();
        if (effectiveFrom == null || effectiveFrom.getNano() % 1_000 != 0
                || !effectiveFrom.isAfter(now)) {
            throw new IllegalArgumentException("T7 must be a future UTC microsecond timestamp");
        }
        List<RuleVersion> current = versions.findAllExecutableVersions(now);
        if (current.size() != 14 || RulePolicyVersion.from(current.stream()
                .map(this::snapshot).toList()) != 6
                || rules.findByRuleCode(ScenarioOpenBankingRuleVersionContract.CODE).isPresent()) {
            throw new IllegalStateException("Complete Rule v6 set is required");
        }
        FraudRule rule = rules.saveAndFlush(FraudRule.create(
                ScenarioOpenBankingRuleVersionContract.CODE,
                "SCN-008 synthetic Rule 15", "Verified multi-account concentration and resend"));
        var condition = JsonNodeFactory.instance.objectNode()
                .put("contractVersion", "SCN008-contract-v1")
                .put("signal", ScenarioOpenBankingRuleVersionContract.SIGNAL);
        RuleVersion version = RuleVersion.draft(rule, 1,
                ScenarioOpenBankingRuleVersionContract.CODE, 50,
                condition, effectiveFrom, null);
        version.publish(now.truncatedTo(ChronoUnit.MICROS));
        return versions.saveAllAndFlush(List.of(version));
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
