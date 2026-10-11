package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoreGroupId;
import com.aifds.backend.rule.client.dto.RuleScoreGroupSummaryResponse;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.rule.client.dto.RuleTransactionType;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Independent Backend recomputation of the approved synthetic Rule v4 contract. */
public final class ScenarioRulePolicy {
    private static final int[] WEIGHTS = {15, 20, 50, 10, 40, 20, 20, 15, 10, 10};
    private static final RuleScoreGroupId[] GROUPS = {
            RuleScoreGroupId.amount, RuleScoreGroupId.security,
            RuleScoreGroupId.security, RuleScoreGroupId.beneficiary,
            RuleScoreGroupId.external_recipient, RuleScoreGroupId.amount,
            RuleScoreGroupId.security, RuleScoreGroupId.security,
            RuleScoreGroupId.security, RuleScoreGroupId.security
    };

    private ScenarioRulePolicy() { }

    public static RuleScoringResultResponse evaluate(FinancialTransaction tx,
                                                     ScenarioFacts facts,
                                                     boolean recipientRiskMatched) {
        if (!"KRW".equals(tx.getCurrencyCode())) {
            throw new IllegalArgumentException("Rule v4 requires KRW");
        }
        return evaluate(tx.getAmount(), tx.getOccurredAt(),
                RuleTransactionType.valueOf(tx.getTransactionType().name()),
                facts, recipientRiskMatched);
    }

    public static RuleScoringResultResponse evaluate(BigDecimal amount, Instant t,
                                                     RuleTransactionType type, ScenarioFacts facts,
                                                     boolean recipientRiskMatched) {
        boolean transfer = type == RuleTransactionType.ACCOUNT_TRANSFER
                || type == RuleTransactionType.OPEN_BANKING_TRANSFER;
        boolean r001 = transfer && amount.compareTo(new BigDecimal("10000000")) >= 0;
        boolean r006 = "AVAILABLE".equals(facts.baselineStatus())
                && facts.baselineCount() >= 10 && facts.baselineDays() >= 3
                && facts.baselineTotal() != null && facts.baselineTotal().signum() > 0
                && amount.compareTo(new BigDecimal("1000000")) >= 0
                && amount.multiply(BigDecimal.valueOf(facts.baselineCount()))
                        .compareTo(facts.baselineTotal().multiply(BigDecimal.TEN)) >= 0;
        boolean r002 = "NEW_UNVERIFIED".equals(facts.deviceStatus())
                && facts.deviceEventId() != null && (r001 || r006);
        boolean r003 = "RAISED".equals(facts.limitStatus())
                && facts.passwordEventId() != null && facts.limitEventId() != null
                && withinStrict(facts.passwordChangedAt(), facts.limitChangedAt(), 30)
                && withinStrict(facts.limitChangedAt(), t, 30)
                && withinStrict(facts.passwordChangedAt(), t, 60)
                && (r001 || r006 || "TRUE".equals(facts.priorLimitExceeded()));
        boolean r004 = "FIRST".equals(facts.recipientStatus())
                && facts.beneficiaryEventId() != null;
        boolean r005 = recipientRiskMatched;
        boolean r007 = facts.exactProviderDeviceMatch();
        boolean r008 = "ALERT".equals(facts.appSecurityStatus());
        boolean r009 = r003 && facts.previousLimit() != null && facts.newLimit() != null
                && facts.newLimit().compareTo(facts.previousLimit().multiply(BigDecimal.valueOf(2))) >= 0
                && facts.newLimit().subtract(facts.previousLimit())
                        .compareTo(new BigDecimal("5000000")) >= 0;
        boolean r010 = "OVERSEAS".equals(facts.networkRegionStatus());
        boolean[] matched = {r001, r002 && transfer, r003 && transfer,
                r004 && transfer, r005 && transfer, r006 && transfer,
                r007 && transfer, r008 && transfer, r009 && transfer,
                r010 && transfer};

        var caps = new EnumMap<RuleScoreGroupId, Integer>(RuleScoreGroupId.class);
        caps.put(RuleScoreGroupId.amount, 20);
        caps.put(RuleScoreGroupId.security, 60);
        caps.put(RuleScoreGroupId.beneficiary, 10);
        caps.put(RuleScoreGroupId.external_recipient, 40);
        var raw = new EnumMap<RuleScoreGroupId, Integer>(RuleScoreGroupId.class);
        var applied = new EnumMap<RuleScoreGroupId, Integer>(RuleScoreGroupId.class);
        for (RuleScoreGroupId group : RuleScoreGroupId.values()) {
            raw.put(group, 0);
            applied.put(group, 0);
        }
        List<RuleContributionResponse> contributions = new ArrayList<>();
        int acceptedTotal = 0;
        RuleId[] ids = RuleId.values();
        if (ids.length != 10) throw new IllegalStateException("Unexpected Rule IDs");
        for (int i = 0; i < matched.length; i++) {
            RuleScoreGroupId group = GROUPS[i];
            int original = matched[i] ? WEIGHTS[i] : 0;
            int accepted = Math.min(original,
                    Math.min(caps.get(group) - applied.get(group), 100 - acceptedTotal));
            raw.put(group, raw.get(group) + original);
            applied.put(group, applied.get(group) + accepted);
            acceptedTotal += accepted;
            contributions.add(new RuleContributionResponse(ids[i], i + 1,
                    matched[i], original, accepted));
        }
        List<RuleScoreGroupSummaryResponse> summaries = new ArrayList<>();
        int total = 0;
        for (RuleScoreGroupId group : RuleScoreGroupId.values()) {
            int accepted = applied.get(group);
            summaries.add(new RuleScoreGroupSummaryResponse(group, raw.get(group),
                    caps.get(group), accepted, raw.get(group) - accepted));
            total += accepted;
        }
        if (total != acceptedTotal) throw new IllegalStateException("Attribution mismatch");
        RuleRiskLevel level = total < 20 ? RuleRiskLevel.LOW
                : total < 50 ? RuleRiskLevel.MEDIUM
                : total < 80 ? RuleRiskLevel.HIGH : RuleRiskLevel.CRITICAL;
        return new RuleScoringResultResponse("scoring-policy-v4", total, level,
                contributions, summaries);
    }

    private static boolean withinStrict(Instant earlier, Instant later, long minutes) {
        return earlier != null && later != null && earlier.isBefore(later)
                && Duration.between(earlier, later).compareTo(Duration.ofMinutes(minutes)) <= 0;
    }
}
