package com.aifds.backend.rule.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.scenario.ScenarioFundFlowRuleVersionContract;
import com.aifds.backend.scenario.ScenarioRuleVersionContract;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Explicit future publication; all twelve existing Rule v5 versions stay immutable. */
@Service
public class RuleV6LocalPublicationService {
    private final RuleVersionRepository versions;
    private final FraudRuleRepository rules;
    private final DatabaseTransactionTimestampProvider databaseTime;

    public RuleV6LocalPublicationService(RuleVersionRepository versions,
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
            throw new IllegalArgumentException("T6 must be a future UTC microsecond timestamp");
        }
        List<RuleVersion> current = versions.findAllExecutableVersions(now);
        if (current.size() != 12 || RulePolicyVersion.from(current.stream()
                .map(this::snapshot).toList()) != 5) {
            throw new IllegalStateException("Complete Rule v5 set is required");
        }
        for (String code : ScenarioFundFlowRuleVersionContract.CODES) {
            if (rules.findByRuleCode(code).isPresent()) {
                throw new IllegalStateException("Rule v6 code already exists");
            }
        }
        Instant publishedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<RuleVersion> created = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String code = ScenarioFundFlowRuleVersionContract.CODES.get(i);
            FraudRule rule = rules.saveAndFlush(FraudRule.create(code,
                    "SCN-006/007 synthetic Rule " + (i + 13),
                    "Versioned local synthetic source-to-use link"));
            String signal = i == 0 ? "APPROVED_INBOUND_LINKED_ATM_EIGHTY_PERCENT"
                    : "APPROVED_LOAN_LINKED_NEW_RECIPIENT_EIGHTY_PERCENT";
            var condition = JsonNodeFactory.instance.objectNode()
                    .put("contractVersion", "SCN006-007-contract-v1")
                    .put("signal", signal);
            RuleVersion version = RuleVersion.draft(rule, 1, code, 50,
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
