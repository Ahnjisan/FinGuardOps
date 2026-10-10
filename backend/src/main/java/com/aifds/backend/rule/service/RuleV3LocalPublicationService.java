package com.aifds.backend.rule.service;

import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Explicit, atomic local publication of R001-R004 v3 and R005 v1. */
@Service
public class RuleV3LocalPublicationService {
    private static final List<String> EXISTING_CODES = List.of(
            RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            RuleV1ContractRegistry.RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER);

    private final RuleVersionRepository versions;
    private final FraudRuleRepository rules;
    private final Clock clock;

    public RuleV3LocalPublicationService(RuleVersionRepository versions,
                                         FraudRuleRepository rules, Clock clock) {
        this.versions = versions;
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional
    public List<RuleVersion> publish(Instant effectiveFrom) {
        Instant now = clock.instant();
        if (effectiveFrom == null || effectiveFrom.getNano() % 1_000 != 0
                || !effectiveFrom.isAfter(now)) {
            throw new IllegalArgumentException("v3 effectiveFrom must be a future microsecond timestamp");
        }
        if (rules.findByRuleCode(
                RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT).isPresent()) {
            throw new IllegalStateException("R005 already exists");
        }
        List<RuleVersion> candidates = EXISTING_CODES.stream().map(code ->
                versions.findByFraudRule_RuleCodeAndVersionNumber(code, 2)
                        .orElseThrow(() -> new IllegalStateException("Complete v2 set is required")))
                .toList();
        List<RuleVersion> locked = versions.findAllByRuleVersionIdInForUpdate(
                candidates.stream().map(RuleVersion::getRuleVersionId).toList());
        if (locked.size() != 4) {
            throw new IllegalStateException("Complete locked v2 set is required");
        }
        Instant publishedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<RuleVersion> created = new ArrayList<>();
        for (String code : EXISTING_CODES) {
            RuleVersion previous = locked.stream().filter(item ->
                    item.getFraudRule().getRuleCode().equals(code)).findFirst().orElseThrow();
            if (previous.getStatus() != RuleVersionStatus.PUBLISHED
                    || previous.getVersionNumber() != 2
                    || previous.getEffectiveTo() != null
                    || !effectiveFrom.isAfter(previous.getEffectiveFrom())
                    || versions.findByFraudRule_RuleCodeAndVersionNumber(code, 3).isPresent()) {
                throw new IllegalStateException("v2 publication boundary does not match");
            }
            RuleV1ExecutionPlanRegistry.requireExecutionCompatible(code,
                    previous.getReasonCode(), previous.getWeight(),
                    previous.getConditionDefinition());
            previous.closeEffectivePeriod(effectiveFrom);
            RuleVersion next = RuleVersion.draft(previous.getFraudRule(), 3,
                    previous.getReasonCode(), previous.getWeight(),
                    previous.getConditionDefinition(), effectiveFrom, null);
            next.publish(publishedAt);
            created.add(next);
        }
        versions.saveAllAndFlush(locked);
        String r005Code = RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT;
        FraudRule r005 = rules.saveAndFlush(FraudRule.create(r005Code,
                "External suspicious recipient account",
                "Validated provider recipient match within 24 hours of transfer cutoff"));
        var condition = JsonNodeFactory.instance.objectNode()
                .put("subjectType", "RECIPIENT_ACCOUNT")
                .put("riskType", "SUSPICIOUS_ACCOUNT")
                .put("reasonCode", "SUSPICIOUS_RECIPIENT_ACCOUNT")
                .put("freshnessSeconds", 86_400);
        RuleV1ExecutionPlanRegistry.requireExecutionCompatible(r005Code,
                r005Code, 40, condition);
        RuleVersion r005Version = RuleVersion.draft(r005, 1,
                r005Code, 40, condition,
                effectiveFrom, null);
        r005Version.publish(publishedAt);
        created.add(r005Version);
        return versions.saveAllAndFlush(created);
    }
}
