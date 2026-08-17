package com.aifds.backend.rule.service;

import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1DefaultRuleSetDefinition;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.FraudRuleLifecycleStatus;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RuleV1DefaultRuleSetPublicationService {

    private static final int DEFAULT_RULE_COUNT = 4;

    private final RuleVersionRepository ruleVersionRepository;
    private final FraudRuleRepository fraudRuleRepository;
    private final CanonicalRuleSetVersionCalculator ruleSetVersionCalculator;
    private final Clock clock;

    public RuleV1DefaultRuleSetPublicationService(
            RuleVersionRepository ruleVersionRepository,
            FraudRuleRepository fraudRuleRepository,
            Clock clock
    ) {
        this.ruleVersionRepository = ruleVersionRepository;
        this.fraudRuleRepository = fraudRuleRepository;
        this.clock = clock;
        this.ruleSetVersionCalculator =
                new CanonicalRuleSetVersionCalculator();
    }

    @Transactional
    public RuleV1DefaultRuleSetPublicationResult publish(
            Instant effectiveFrom
    ) {
        Instant requestedEffectiveFrom = requireMicrosecondPrecision(
                effectiveFrom,
                "effectiveFrom"
        );
        List<RuleV1DefaultRuleSetDefinition.DefaultRule> definitions =
                RuleV1DefaultRuleSetDefinition.rules();
        List<UUID> ruleVersionIds = definitions.stream()
                .map(RuleV1DefaultRuleSetDefinition.DefaultRule::ruleVersionId)
                .toList();
        List<UUID> fraudRuleIds = definitions.stream()
                .map(RuleV1DefaultRuleSetDefinition.DefaultRule::fraudRuleId)
                .toList();

        List<RuleVersion> lockedVersions = ruleVersionRepository
                .findAllByRuleVersionIdInForUpdate(ruleVersionIds);
        List<FraudRule> lockedRules = fraudRuleRepository
                .findAllByFraudRuleIdInForUpdate(fraudRuleIds);
        List<RuleVersion> canonicalVersions = validateAndOrder(
                definitions,
                lockedVersions,
                lockedRules
        );
        List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities =
                identities(canonicalVersions);
        List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonicalRules =
                RuleV1ExecutionPlanRegistry.canonicalize(identities);
        requireExactDefaultOrder(definitions, canonicalRules);

        Set<RuleVersionStatus> statuses = new HashSet<>();
        canonicalVersions.forEach(version -> statuses.add(version.getStatus()));
        if (statuses.equals(Set.of(RuleVersionStatus.PUBLISHED))) {
            return alreadyPublishedResult(
                    canonicalVersions,
                    requestedEffectiveFrom,
                    ruleVersionIds,
                    ruleSetVersionCalculator.calculate(identities)
            );
        }
        if (!statuses.equals(Set.of(RuleVersionStatus.DRAFT))) {
            throw new IllegalStateException(
                    "Default Rule v1 versions must be all DRAFT or all PUBLISHED"
            );
        }
        requirePristineDrafts(canonicalVersions);

        Instant publicationTime = clock.instant();
        if (!requestedEffectiveFrom.isAfter(publicationTime)) {
            throw new IllegalArgumentException(
                    "effectiveFrom must be later than the publication time"
            );
        }
        Instant publishedAt = publicationTime.truncatedTo(ChronoUnit.MICROS);
        for (RuleVersion version : canonicalVersions) {
            version.updateDraft(
                    version.getReasonCode(),
                    version.getWeight(),
                    version.getConditionDefinition(),
                    requestedEffectiveFrom,
                    null
            );
            version.publish(publishedAt);
        }
        ruleVersionRepository.saveAllAndFlush(canonicalVersions);
        String ruleSetVersion = ruleSetVersionCalculator.calculate(identities);

        return new RuleV1DefaultRuleSetPublicationResult(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .PUBLISHED,
                ruleVersionIds,
                requestedEffectiveFrom,
                publishedAt,
                ruleSetVersion
        );
    }

    private List<RuleVersion> validateAndOrder(
            List<RuleV1DefaultRuleSetDefinition.DefaultRule> definitions,
            List<RuleVersion> lockedVersions,
            List<FraudRule> lockedRules
    ) {
        if (lockedVersions.size() != DEFAULT_RULE_COUNT
                || lockedRules.size() != DEFAULT_RULE_COUNT) {
            throw new IllegalStateException(
                    "The complete V5 default Rule v1 set does not exist"
            );
        }
        Map<UUID, RuleVersion> versionsById = uniqueVersions(lockedVersions);
        Map<UUID, FraudRule> rulesById = uniqueRules(lockedRules);

        return definitions.stream().map(definition -> {
            RuleVersion version = versionsById.get(definition.ruleVersionId());
            FraudRule rule = rulesById.get(definition.fraudRuleId());
            if (version == null || rule == null) {
                throw new IllegalStateException(
                        "A required V5 default Rule v1 identity is missing"
                );
            }
            validateExactIdentity(definition, version, rule);
            validateExactMetadata(version, rule);
            return version;
        }).toList();
    }

    private Map<UUID, RuleVersion> uniqueVersions(
            List<RuleVersion> versions
    ) {
        Map<UUID, RuleVersion> result = new HashMap<>();
        for (RuleVersion version : versions) {
            Objects.requireNonNull(version, "RuleVersion must not be null");
            if (result.put(version.getRuleVersionId(), version) != null) {
                throw new IllegalStateException("Duplicate RuleVersion identity");
            }
        }
        return result;
    }

    private Map<UUID, FraudRule> uniqueRules(List<FraudRule> rules) {
        Map<UUID, FraudRule> result = new HashMap<>();
        for (FraudRule rule : rules) {
            Objects.requireNonNull(rule, "FraudRule must not be null");
            if (result.put(rule.getFraudRuleId(), rule) != null) {
                throw new IllegalStateException("Duplicate FraudRule identity");
            }
        }
        return result;
    }

    private void validateExactIdentity(
            RuleV1DefaultRuleSetDefinition.DefaultRule definition,
            RuleVersion version,
            FraudRule lockedRule
    ) {
        FraudRule versionRule = Objects.requireNonNull(
                version.getFraudRule(),
                "RuleVersion FraudRule must not be null"
        );
        if (!definition.ruleVersionId().equals(version.getRuleVersionId())
                || !definition.fraudRuleId().equals(
                versionRule.getFraudRuleId()
        )
                || !definition.fraudRuleId().equals(
                lockedRule.getFraudRuleId()
        )
                || !Objects.equals(versionRule.getId(), lockedRule.getId())
                || !definition.ruleCode().equals(versionRule.getRuleCode())
                || !definition.ruleCode().equals(lockedRule.getRuleCode())
                || definition.versionNumber() != version.getVersionNumber()) {
            throw new IllegalStateException(
                    "Default Rule v1 identity does not match the V5 contract"
            );
        }
        if (lockedRule.getLifecycleStatus()
                != FraudRuleLifecycleStatus.ACTIVE
                || versionRule.getLifecycleStatus()
                != FraudRuleLifecycleStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Default Rule v1 FraudRules must be ACTIVE"
            );
        }
    }

    private void validateExactMetadata(
            RuleVersion version,
            FraudRule rule
    ) {
        RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                rule.getRuleCode(),
                version.getReasonCode(),
                version.getWeight(),
                version.getConditionDefinition()
        );
        if (version.getEffectiveTo() != null) {
            throw new IllegalStateException(
                    "Default Rule v1 versions must be open-ended"
            );
        }
    }

    private List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities(
            List<RuleVersion> versions
    ) {
        return versions.stream().map(version ->
                new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                        version.getFraudRule().getFraudRuleId(),
                        version.getRuleVersionId(),
                        version.getFraudRule().getRuleCode(),
                        version.getVersionNumber()
                )
        ).toList();
    }

    private void requireExactDefaultOrder(
            List<RuleV1DefaultRuleSetDefinition.DefaultRule> definitions,
            List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonicalRules
    ) {
        if (canonicalRules.size() != DEFAULT_RULE_COUNT) {
            throw new IllegalStateException(
                    "The default Rule v1 set must contain exactly four rules"
            );
        }
        for (int index = 0; index < definitions.size(); index++) {
            RuleV1DefaultRuleSetDefinition.DefaultRule definition =
                    definitions.get(index);
            RuleV1ExecutionPlanRegistry.CanonicalRule canonical =
                    canonicalRules.get(index);
            if (canonical.executionOrder() != definition.canonicalOrder()
                    || canonical.capability().ruleId() != definition.ruleId()
                    || !canonical.identity().ruleVersionId().equals(
                    definition.ruleVersionId()
            )) {
                throw new IllegalStateException(
                        "Default Rule v1 canonical order does not match"
                );
            }
        }
    }

    private void requirePristineDrafts(List<RuleVersion> versions) {
        for (RuleVersion version : versions) {
            if (version.getEffectiveFrom() != null
                    || version.getEffectiveTo() != null
                    || version.getPublishedAt() != null) {
                throw new IllegalStateException(
                        "Default Rule v1 DRAFT period metadata must be unset"
                );
            }
        }
    }

    private RuleV1DefaultRuleSetPublicationResult alreadyPublishedResult(
            List<RuleVersion> versions,
            Instant effectiveFrom,
            List<UUID> ruleVersionIds,
            String ruleSetVersion
    ) {
        Set<Instant> publishedTimes = new HashSet<>();
        for (RuleVersion version : versions) {
            if (!effectiveFrom.equals(version.getEffectiveFrom())) {
                throw new IllegalStateException(
                        "Published effectiveFrom does not match the request"
                );
            }
            publishedTimes.add(Objects.requireNonNull(
                    version.getPublishedAt(),
                    "Published RuleVersion must have publishedAt"
            ));
        }
        if (publishedTimes.size() != 1) {
            throw new IllegalStateException(
                    "Default Rule v1 versions must share one publishedAt"
            );
        }
        return new RuleV1DefaultRuleSetPublicationResult(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .ALREADY_PUBLISHED,
                ruleVersionIds,
                effectiveFrom,
                publishedTimes.iterator().next(),
                ruleSetVersion
        );
    }

    private Instant requireMicrosecondPrecision(
            Instant value,
            String field
    ) {
        Instant required = Objects.requireNonNull(
                value,
                field + " must not be null"
        );
        if (required.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    field + " must use at most microsecond precision"
            );
        }
        return required;
    }
}
