package com.aifds.backend.detection.service;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventType;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleTransactionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleTransactionType;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.FraudRuleLifecycleStatus;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class RuleAnalysisSnapshotAssembler {

    static final int MAX_BEHAVIOR_EVENTS = 1_000;

    private final RuleVersionRepository ruleVersionRepository;
    private final BehaviorEventRepository behaviorEventRepository;
    private final CanonicalRuleSetVersionCalculator ruleSetVersionCalculator;

    public RuleAnalysisSnapshotAssembler(
            RuleVersionRepository ruleVersionRepository,
            BehaviorEventRepository behaviorEventRepository
    ) {
        this.ruleVersionRepository = ruleVersionRepository;
        this.behaviorEventRepository = behaviorEventRepository;
        this.ruleSetVersionCalculator =
                new CanonicalRuleSetVersionCalculator();
    }

    public AssembledRuleAnalysisSnapshot assemble(
            FinancialTransaction transaction
    ) {
        FinancialTransaction source = Objects.requireNonNull(
                transaction,
                "transaction must not be null"
        );
        Instant cutoff = Objects.requireNonNull(
                source.getOccurredAt(),
                "transaction occurredAt must not be null"
        );
        RuleTransactionSnapshotRequest transactionSnapshot =
                toTransactionSnapshot(source);
        List<RuleVersion> executableVersions = List.copyOf(
                ruleVersionRepository.findAllExecutableVersions(cutoff)
        );

        Map<UUID, RuleVersion> versionsById = new HashMap<>();
        List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities =
                executableVersions.stream()
                        .map(version -> validateAndIdentify(
                                version,
                                cutoff,
                                versionsById
                        ))
                        .toList();
        List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonicalRules =
                RuleV1ExecutionPlanRegistry.canonicalize(identities);
        String ruleSetVersion = ruleSetVersionCalculator.calculate(identities);

        List<RuleVersionSnapshotRequest> ruleSnapshots = canonicalRules.stream()
                .map(rule -> toRuleSnapshot(
                        versionsById.get(rule.identity().ruleVersionId())
                ))
                .toList();
        List<RuleBehaviorEventSnapshotRequest> behaviorSnapshots =
                behaviorSnapshots(source, cutoff, canonicalRules);
        RuleAnalysisRequest request = new RuleAnalysisRequest(
                cutoff,
                transactionSnapshot,
                behaviorSnapshots,
                ruleSnapshots
        );
        return new AssembledRuleAnalysisSnapshot(ruleSetVersion, request);
    }

    private RuleV1ExecutionPlanRegistry.RuleVersionIdentity validateAndIdentify(
            RuleVersion version,
            Instant cutoff,
            Map<UUID, RuleVersion> versionsById
    ) {
        Objects.requireNonNull(version, "RuleVersion must not be null");
        FraudRule rule = Objects.requireNonNull(
                version.getFraudRule(),
                "FraudRule must not be null"
        );
        if (rule.getLifecycleStatus() != FraudRuleLifecycleStatus.ACTIVE
                || version.getStatus()
                != com.aifds.backend.rule.entity.RuleVersionStatus.PUBLISHED
                || version.getEffectiveFrom().isAfter(cutoff)
                || version.getEffectiveTo() != null
                && !cutoff.isBefore(version.getEffectiveTo())) {
            throw new IllegalStateException(
                    "Repository returned a non-executable RuleVersion"
            );
        }
        RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                rule.getRuleCode(),
                version.getReasonCode(),
                version.getWeight(),
                version.getConditionDefinition()
        );
        if (versionsById.put(version.getRuleVersionId(), version) != null) {
            throw new IllegalArgumentException("Duplicate ruleVersionId");
        }
        return new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                rule.getFraudRuleId(),
                version.getRuleVersionId(),
                rule.getRuleCode(),
                version.getVersionNumber()
        );
    }

    private List<RuleBehaviorEventSnapshotRequest> behaviorSnapshots(
            FinancialTransaction transaction,
            Instant cutoff,
            List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonicalRules
    ) {
        Set<BehaviorEventType> eventTypes = EnumSet.noneOf(
                BehaviorEventType.class
        );
        int maxWindowSeconds = 0;
        for (RuleV1ExecutionPlanRegistry.CanonicalRule rule : canonicalRules) {
            maxWindowSeconds = Math.max(
                    maxWindowSeconds,
                    rule.capability().windowSeconds()
            );
            rule.capability().requiredBehaviorEventTypes().stream()
                    .map(value -> BehaviorEventType.valueOf(value.name()))
                    .forEach(eventTypes::add);
        }
        if (eventTypes.isEmpty()) {
            return List.of();
        }

        Instant fromInclusive;
        try {
            fromInclusive = cutoff.minusSeconds(maxWindowSeconds);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Behavior event window cannot be calculated",
                    exception
            );
        }
        return behaviorEventRepository.findForRuleEvaluation(
                        transaction.getExternalCustomerRef(),
                        Set.copyOf(eventTypes),
                        fromInclusive,
                        cutoff,
                        PageRequest.of(0, MAX_BEHAVIOR_EVENTS)
                ).stream()
                .map(this::toBehaviorSnapshot)
                .toList();
    }

    private RuleTransactionSnapshotRequest toTransactionSnapshot(
            FinancialTransaction transaction
    ) {
        return new RuleTransactionSnapshotRequest(
                transaction.getTransactionId(),
                toRuleTransactionType(transaction.getTransactionType()),
                amountToString(transaction.getAmount()),
                transaction.getCurrencyCode(),
                transaction.getOccurredAt(),
                transaction.getExternalCustomerRef(),
                transaction.getSenderAccountRef(),
                transaction.getRecipientAccountRef(),
                transaction.getDeviceRef()
        );
    }

    private RuleTransactionType toRuleTransactionType(TransactionType type) {
        return switch (type) {
            case ACCOUNT_TRANSFER -> RuleTransactionType.ACCOUNT_TRANSFER;
            case OPEN_BANKING_TRANSFER ->
                    RuleTransactionType.OPEN_BANKING_TRANSFER;
            case ATM_WITHDRAWAL, LOAN_DISBURSED -> throw new IllegalArgumentException(
                    "Transaction type is not supported by Rule v1"
            );
        };
    }

    private String amountToString(BigDecimal amount) {
        BigDecimal value = Objects.requireNonNull(
                amount,
                "transaction amount must not be null"
        ).stripTrailingZeros();
        if (value.signum() <= 0 || value.scale() > 0) {
            throw new IllegalArgumentException(
                    "Transaction amount must be a positive integer"
            );
        }
        return value.toPlainString();
    }

    private RuleBehaviorEventSnapshotRequest toBehaviorSnapshot(
            BehaviorEvent event
    ) {
        RuleBehaviorEventType eventType = switch (event.getEventType()) {
            case DEVICE_REGISTERED -> RuleBehaviorEventType.DEVICE_REGISTERED;
            case PASSWORD_CHANGED -> RuleBehaviorEventType.PASSWORD_CHANGED;
            case TRANSFER_LIMIT_CHANGED ->
                    RuleBehaviorEventType.TRANSFER_LIMIT_CHANGED;
            case BENEFICIARY_REGISTERED ->
                    RuleBehaviorEventType.BENEFICIARY_REGISTERED;
            default -> throw new IllegalArgumentException(
                    "Behavior event type is not supported by Rule v1"
            );
        };
        return new RuleBehaviorEventSnapshotRequest(
                event.getEventId(),
                eventType,
                event.getOccurredAt(),
                event.getExternalCustomerRef(),
                event.getAccountRef(),
                event.getDeviceRef(),
                event.getBeneficiaryRef()
        );
    }

    private RuleVersionSnapshotRequest toRuleSnapshot(RuleVersion version) {
        FraudRule rule = version.getFraudRule();
        return new RuleVersionSnapshotRequest(
                rule.getFraudRuleId(),
                rule.getRuleCode(),
                RuleLifecycleStatus.ACTIVE,
                version.getRuleVersionId(),
                version.getVersionNumber(),
                com.aifds.backend.rule.client.dto.RuleVersionStatus.PUBLISHED,
                version.getReasonCode(),
                version.getWeight(),
                version.getConditionDefinition(),
                version.getEffectiveFrom(),
                version.getEffectiveTo()
        );
    }

    public record AssembledRuleAnalysisSnapshot(
            String ruleSetVersion,
            RuleAnalysisRequest request
    ) {

        public AssembledRuleAnalysisSnapshot {
            if (ruleSetVersion == null || ruleSetVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "ruleSetVersion must not be blank"
                );
            }
            Objects.requireNonNull(request, "request must not be null");
        }
    }
}
