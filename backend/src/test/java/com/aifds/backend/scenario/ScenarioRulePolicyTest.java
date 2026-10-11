package com.aifds.backend.scenario;

import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRulePolicyTest {
    private static final Instant T = Instant.parse("2026-10-12T12:00:00Z");

    @Test
    void nonKrwInternalTransactionCannotBeScoredAsKrwHighAmount() {
        var usd = new FinancialTransaction(UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER, new BigDecimal("10000000"),
                "USD", T, "customer", "sender", "recipient",
                TransactionChannel.MOBILE_BANKING, "device");
        assertThatThrownBy(() -> ScenarioRulePolicy.evaluate(usd, facts(
                "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN",
                null, null, null), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW");
    }

    @Test
    void newUnverifiedDeviceAndTenfoldBaselineAreMediumWithoutFirstRecipient() {
        var score = ScenarioRulePolicy.evaluate(transaction("1000000"), facts(
                "AVAILABLE", "KNOWN", "NEW_UNVERIFIED", "UNKNOWN",
                null, null, null), false);
        assertThat(score.riskScore()).isEqualTo(40);
        assertThat(score.riskLevel()).isEqualTo(RuleRiskLevel.MEDIUM);
        assertThat(score.ruleContributions().get(1).ruleId()).isEqualTo(RuleId.R002);
        assertThat(score.ruleContributions().get(1).appliedContribution()).isEqualTo(20);
    }

    @Test
    void verifiedNewPhoneDoesNotScoreDeviceAndUnknownBaselineDoesNotBecomeTenfold() {
        var score = ScenarioRulePolicy.evaluate(transaction("1000000"), facts(
                "UNKNOWN", "UNKNOWN", "NEW_VERIFIED", "UNKNOWN",
                null, null, null), false);
        assertThat(score.riskScore()).isZero();
        assertThat(score.riskLevel()).isEqualTo(RuleRiskLevel.LOW);
    }

    @Test
    void passwordThenMaterialRaiseThenTransferCanBeCritical() {
        var score = ScenarioRulePolicy.evaluate(transaction("1000000"), facts(
                "AVAILABLE", "KNOWN", "KNOWN", "RAISED",
                new BigDecimal("2000000"), new BigDecimal("7000000"),
                T.minusSeconds(1200)), false);
        assertThat(score.riskScore()).isEqualTo(80);
        assertThat(score.riskLevel()).isEqualTo(RuleRiskLevel.CRITICAL);
        assertThat(score.ruleContributions().get(2).appliedContribution()).isEqualTo(50);
        assertThat(score.ruleContributions().get(8).appliedContribution()).isEqualTo(10);
    }

    @Test
    void equalTimeOrLimitDecreaseDoesNotTriggerSecuritySequence() {
        var sameTime = ScenarioRulePolicy.evaluate(transaction("1000000"), facts(
                "AVAILABLE", "KNOWN", "KNOWN", "RAISED",
                new BigDecimal("2000000"), new BigDecimal("7000000"),
                T.minusSeconds(600)), false);
        assertThat(sameTime.ruleContributions().get(2).matched()).isFalse();
        var decrease = ScenarioRulePolicy.evaluate(transaction("1000000"), facts(
                "AVAILABLE", "KNOWN", "KNOWN", "NOT_RAISED",
                new BigDecimal("7000000"), new BigDecimal("2000000"),
                T.minusSeconds(1200)), false);
        assertThat(decrease.ruleContributions().get(2).matched()).isFalse();
    }

    @Test
    void canonicalOrderAllocatesSecurityCapWithoutMisstatingEvidence() {
        var score = ScenarioRulePolicy.evaluate(transaction("10000000"), facts(
                "AVAILABLE", "FIRST", "NEW_UNVERIFIED", "RAISED",
                new BigDecimal("2000000"), new BigDecimal("7000000"),
                T.minusSeconds(1200)), false);
        assertThat(score.ruleContributions().get(1).originalContribution()).isEqualTo(20);
        assertThat(score.ruleContributions().get(1).appliedContribution()).isEqualTo(20);
        assertThat(score.ruleContributions().get(2).originalContribution()).isEqualTo(50);
        assertThat(score.ruleContributions().get(2).appliedContribution()).isEqualTo(40);
        assertThat(score.ruleContributions().get(8).originalContribution()).isEqualTo(10);
        assertThat(score.ruleContributions().get(8).appliedContribution()).isZero();
        assertThat(score.groupSummaries().stream().filter(g -> g.groupId().name().equals("security"))
                .findFirst().orElseThrow().appliedScore()).isEqualTo(60);
    }

    @Test
    void highAmountAtmAndLoanHaveNoTransferRuleContribution() {
        for (TransactionType type : new TransactionType[]{TransactionType.ATM_WITHDRAWAL,
                TransactionType.LOAN_DISBURSED}) {
            var nonTransfer = new FinancialTransaction(UUID.randomUUID(), type,
                    new BigDecimal("10000000"), "KRW", T, "customer", "sender", null,
                    type == TransactionType.ATM_WITHDRAWAL ? TransactionChannel.ATM
                            : TransactionChannel.CORE_BANKING, "device");
            var score = ScenarioRulePolicy.evaluate(nonTransfer, facts(
                    "AVAILABLE", "UNKNOWN", "NEW_UNVERIFIED", "RAISED",
                    new BigDecimal("2000000"), new BigDecimal("7000000"),
                    T.minusSeconds(1200)), false);
            assertThat(score.riskScore()).isZero();
            assertThat(score.ruleContributions()).allSatisfy(rule -> {
                assertThat(rule.matched()).isFalse();
                assertThat(rule.appliedContribution()).isZero();
            });
        }
    }

    private FinancialTransaction transaction(String amount) {
        return new FinancialTransaction(UUID.randomUUID(), TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal(amount), "KRW", T, "customer", "sender", "recipient",
                TransactionChannel.MOBILE_BANKING, "device");
    }

    private ScenarioFacts facts(String baseline, String recipient, String device,
                                String limit, BigDecimal previous, BigDecimal next,
                                Instant passwordAt) {
        Instant limitAt = T.minusSeconds(600);
        return new ScenarioFacts("SCN001-002-contract-v1", baseline, 10, 3,
                new BigDecimal("1000000"), new BigDecimal("100000"), recipient,
                "FIRST".equals(recipient) ? UUID.randomUUID() : null,
                "FIRST".equals(recipient) ? T.minusSeconds(300) : null,
                device, "NEW_UNVERIFIED".equals(device) ? UUID.randomUUID() : null,
                "NEW_UNVERIFIED".equals(device) ? T.minusSeconds(100) : null,
                limit, "PER_TRANSFER", previous, next, "FALSE",
                passwordAt == null ? null : UUID.randomUUID(), passwordAt,
                UUID.randomUUID(), limitAt, "UNKNOWN", "UNKNOWN", null,
                "UNKNOWN", null, false,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, null);
    }
}
