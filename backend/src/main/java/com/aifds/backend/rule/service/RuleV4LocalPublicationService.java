package com.aifds.backend.rule.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.scenario.ScenarioRuleVersionContract;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Explicit atomic T4 publication; existing R001 v3 and R005 v1 remain effective. */
@Service
public class RuleV4LocalPublicationService {
    private final RuleVersionRepository versions;
    private final FraudRuleRepository rules;
    private final DatabaseTransactionTimestampProvider databaseTime;

    public RuleV4LocalPublicationService(RuleVersionRepository versions,
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
            throw new IllegalArgumentException("T4 must be a future UTC microsecond timestamp");
        }
        List<RuleVersion> current = versions.findAllExecutableVersions(now);
        if (current.size() != 5) {
            throw new IllegalStateException("Complete active v3 set is required");
        }
        // The existing exact-set validator rejects mixed and unexpected versions.
        List<RuleVersionSnapshotRequest> snapshots = current.stream()
                .map(this::snapshot).toList();
        if (RulePolicyVersion.from(snapshots) != 3) {
            throw new IllegalStateException("Exact R001-R004 v3 + R005 v1 set is required");
        }
        for (RuleVersionSnapshotRequest item : snapshots) {
            if (ScenarioRuleVersionContract.CODES.get(0).equals(item.ruleCode())
                    || ScenarioRuleVersionContract.CODES.get(4).equals(item.ruleCode())) {
                RuleV1ExecutionPlanRegistry.requireExecutionCompatible(item.ruleCode(),
                        item.reasonCode(), item.weight(), item.conditionDefinition());
            }
        }
        for (int i = 5; i < 10; i++) {
            if (rules.findByRuleCode(ScenarioRuleVersionContract.CODES.get(i)).isPresent()) {
                throw new IllegalStateException("Rule v4 code already exists");
            }
        }
        List<RuleVersion> previous = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String code = ScenarioRuleVersionContract.CODES.get(i);
            RuleVersion version = versions.findByFraudRule_RuleCodeAndVersionNumber(code, 3)
                    .orElseThrow();
            if (version.getStatus() != RuleVersionStatus.PUBLISHED
                    || version.getEffectiveTo() != null
                    || versions.findByFraudRule_RuleCodeAndVersionNumber(code, 4).isPresent()) {
                throw new IllegalStateException("Rule v3 publication boundary mismatch");
            }
            previous.add(version);
        }
        List<RuleVersion> locked = versions.findAllByRuleVersionIdInForUpdate(
                previous.stream().map(RuleVersion::getRuleVersionId).toList());
        if (locked.size() != 3) throw new IllegalStateException("Rule v3 lock mismatch");
        Instant publishedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<RuleVersion> created = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int index = i;
            String code = ScenarioRuleVersionContract.CODES.get(i);
            RuleVersion prior = locked.stream().filter(item ->
                    code.equals(item.getFraudRule().getRuleCode())).findFirst().orElseThrow();
            prior.closeEffectivePeriod(effectiveFrom);
            RuleVersion next = RuleVersion.draft(prior.getFraudRule(), 4,
                    code, ScenarioRuleVersionContract.WEIGHTS.get(index),
                    condition(index), effectiveFrom, null);
            next.publish(publishedAt);
            created.add(next);
        }
        versions.saveAllAndFlush(locked);
        for (int i = 5; i < 10; i++) {
            String code = ScenarioRuleVersionContract.CODES.get(i);
            FraudRule rule = rules.saveAndFlush(FraudRule.create(code,
                    "SCN-001/002 synthetic Rule " + (i + 1),
                    "Versioned local synthetic/Mock detection signal"));
            RuleVersion version = RuleVersion.draft(rule, 1, code,
                    ScenarioRuleVersionContract.WEIGHTS.get(i),
                    condition(i), effectiveFrom, null);
            version.publish(publishedAt);
            created.add(version);
        }
        return versions.saveAllAndFlush(created);
    }

    private com.fasterxml.jackson.databind.JsonNode condition(int index) {
        String signal = switch (index) {
            case 1 -> "NEW_UNVERIFIED_DEVICE";
            case 2 -> "PASSWORD_LIMIT_SEQUENCE";
            case 3 -> "FIRST_REGISTERED_RECIPIENT";
            case 5 -> "TENFOLD_BASELINE";
            case 6 -> "EXACT_RISK_DEVICE";
            case 7 -> "APP_SECURITY_ALERT";
            case 8 -> "MATERIAL_LIMIT_UPLIFT";
            case 9 -> "OVERSEAS_NETWORK";
            default -> throw new IllegalArgumentException("No v4 condition");
        };
        return JsonNodeFactory.instance.objectNode()
                .put("contractVersion", "SCN001-002-contract-v1")
                .put("signal", signal);
    }

    private RuleVersionSnapshotRequest snapshot(RuleVersion version) {
        var rule = version.getFraudRule();
        return new RuleVersionSnapshotRequest(rule.getFraudRuleId(), rule.getRuleCode(),
                com.aifds.backend.rule.client.dto.RuleLifecycleStatus.ACTIVE,
                version.getRuleVersionId(), version.getVersionNumber(),
                com.aifds.backend.rule.client.dto.RuleVersionStatus.PUBLISHED,
                version.getReasonCode(), version.getWeight(),
                version.getConditionDefinition(), version.getEffectiveFrom(),
                version.getEffectiveTo());
    }
}
