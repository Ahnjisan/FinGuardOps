package com.aifds.backend.rule.entity;

import com.aifds.backend.detection.entity.RuleEvidenceObservationSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleDomainTest {

    private static final Instant EFFECTIVE_FROM =
            Instant.parse("2026-08-01T00:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsCanonicalUuidV4BusinessIdsAndSeparateVersions() {
        FraudRule rule = amountRule();
        RuleVersion version = draft(rule, 1);

        assertThat(rule.getFraudRuleId().version()).isEqualTo(4);
        assertThat(rule.getFraudRuleId().variant()).isEqualTo(2);
        assertThat(version.getRuleVersionId().version()).isEqualTo(4);
        assertThat(version.getRuleVersionId().variant()).isEqualTo(2);
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getConcurrencyVersion()).isZero();
    }

    @Test
    void updatesDetailsAndOnlyRetiresOnce() {
        FraudRule rule = amountRule();

        rule.updateDetails("변경 이름", "변경 설명");
        rule.retire();

        assertThat(rule.getName()).isEqualTo("변경 이름");
        assertThat(rule.getDescription()).isEqualTo("변경 설명");
        assertThat(rule.getLifecycleStatus())
                .isEqualTo(FraudRuleLifecycleStatus.RETIRED);
        assertThatThrownBy(rule::retire)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsRuleCodeAndReasonCodeAsIndependentConcepts() {
        FraudRule rule = amountRule();
        RuleVersion version = RuleVersion.draft(
                rule,
                1,
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                15,
                amountCondition(),
                EFFECTIVE_FROM,
                null
        );

        assertThat(version.getFraudRule().getRuleCode())
                .isEqualTo(
                        RuleEvidenceObservationSummary
                                .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                );
        assertThat(version.getReasonCode())
                .isEqualTo(
                        RuleEvidenceObservationSummary
                                .RECENT_BENEFICIARY_TRANSFER
                );
    }

    @Test
    void validatesRuleAndReasonCodesAndWeightRange() {
        assertThatThrownBy(() -> FraudRule.create(
                "lower_case",
                "이름",
                "설명"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleVersion.draft(
                amountRule(),
                1,
                "UNSUPPORTED_REASON",
                15,
                amountCondition(),
                EFFECTIVE_FROM,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft(amountRule(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft(amountRule(), 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleVersion.draft(
                amountRule(),
                0,
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition(),
                EFFECTIVE_FROM,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsDraftUpdatesAndApprovedTransitions() {
        RuleVersion published = draft(amountRule(), 15);
        published.updateDraft(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                20,
                amountCondition().put("amountThreshold", "20000000"),
                EFFECTIVE_FROM.plusSeconds(60),
                null
        );
        published.publish(EFFECTIVE_FROM.minusSeconds(60));

        RuleVersion withdrawn = draft(amountRule(), 15);
        withdrawn.withdraw();

        assertThat(published.getStatus())
                .isEqualTo(RuleVersionStatus.PUBLISHED);
        assertThat(published.getWeight()).isEqualTo(20);
        assertThat(withdrawn.getStatus())
                .isEqualTo(RuleVersionStatus.WITHDRAWN);
        assertThatThrownBy(withdrawn::withdraw)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPublishingWithoutStartOrForRetiredRule() {
        FraudRule active = amountRule();
        RuleVersion missingStart = RuleVersion.draft(
                active,
                1,
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition(),
                null,
                null
        );
        assertThatThrownBy(
                () -> missingStart.publish(EFFECTIVE_FROM)
        ).isInstanceOf(IllegalStateException.class);

        FraudRule retired = amountRule();
        RuleVersion retiredVersion = draft(retired, 15);
        retired.retire();
        assertThatThrownBy(
                () -> retiredVersion.publish(EFFECTIVE_FROM)
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void makesPublishedDefinitionImmutableAndClosesOnlyOnce() {
        RuleVersion version = draft(amountRule(), 15);
        version.publish(EFFECTIVE_FROM.minusSeconds(60));

        assertThatThrownBy(() -> version.updateDraft(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                20,
                amountCondition(),
                EFFECTIVE_FROM,
                null
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(version::withdraw)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                () -> version.closeEffectivePeriod(EFFECTIVE_FROM)
        ).isInstanceOf(IllegalArgumentException.class);

        version.closeEffectivePeriod(EFFECTIVE_FROM.plusSeconds(3600));
        assertThat(version.getEffectiveTo())
                .isEqualTo(EFFECTIVE_FROM.plusSeconds(3600));
        assertThatThrownBy(
                () -> version.closeEffectivePeriod(
                        EFFECTIVE_FROM.plusSeconds(7200)
                )
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnsDefensiveConditionCopies() {
        ObjectNode input = amountCondition();
        RuleVersion version = RuleVersion.draft(
                amountRule(),
                1,
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                input,
                EFFECTIVE_FROM,
                null
        );

        input.put("amountThreshold", "1");
        ObjectNode first = (ObjectNode) version.getConditionDefinition();
        first.put("amountThreshold", "2");

        assertThat(version.getConditionDefinition()
                .get("amountThreshold").textValue()).isEqualTo("10000000");
    }

    private FraudRule amountRule() {
        return FraudRule.create(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                "절대 고액 이체",
                "현재 거래의 절대 금액을 평가한다."
        );
    }

    private RuleVersion draft(FraudRule rule, int weight) {
        return RuleVersion.draft(
                rule,
                1,
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                weight,
                amountCondition(),
                EFFECTIVE_FROM,
                null
        );
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }
}
