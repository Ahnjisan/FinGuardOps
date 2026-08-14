package com.aifds.backend.rule.contract;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleV1ContractRegistryTest {

    @Test
    void exposesEachRuleV1RuleCodeToReasonCodeMapping() {
        assertThat(RuleV1ContractRegistry.allowedReasonCodesFor(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
        )).isEqualTo(Set.of(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
        ));
        assertThat(RuleV1ContractRegistry.allowedReasonCodesFor(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
        )).isEqualTo(Set.of(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
        ));
        assertThat(RuleV1ContractRegistry.allowedReasonCodesFor(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT
        )).isEqualTo(Set.of(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT
        ));
        assertThat(RuleV1ContractRegistry.allowedReasonCodesFor(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
        )).isEqualTo(Set.of(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
        ));
    }

    @Test
    void acceptsEveryRegisteredRuleV1Combination() {
        RuleV1ContractRegistry.requireCompatible(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
        );
        RuleV1ContractRegistry.requireCompatible(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
        );
        RuleV1ContractRegistry.requireCompatible(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT
        );
        RuleV1ContractRegistry.requireCompatible(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
        );
    }

    @Test
    void rejectsUnsupportedAndIncompatibleCodes() {
        assertThatThrownBy(() ->
                RuleV1ContractRegistry.requireCompatible(
                        "UNSUPPORTED_RULE",
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_RULE")
                .hasMessageContaining("ruleCode");
        assertThatThrownBy(() ->
                RuleV1ContractRegistry.requireCompatible(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        "UNSUPPORTED_REASON"
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_REASON")
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() ->
                RuleV1ContractRegistry.requireCompatible(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        RuleV1ContractRegistry
                                .RECENT_BENEFICIARY_TRANSFER
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        RuleV1ContractRegistry
                                .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                )
                .hasMessageContaining(
                        RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
                );
    }

    @Test
    void ownsRuleAnalysisMetadataAndApprovedDisplayDescriptions() {
        assertThat(RuleV1ContractRegistry.ruleAnalysisMetadata())
                .satisfies(metadata -> {
                    assertThat(metadata.scoringPolicyVersion())
                            .isEqualTo("scoring-policy-v1");
                    assertThat(metadata.featureVersion())
                            .isEqualTo("rule-v1");
                    assertThat(metadata.modelVersion()).isNull();
                });
        assertThat(RuleV1ContractRegistry.displayDescriptionFor(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
        )).isEqualTo("절대 고액 이체");
        assertThat(RuleV1ContractRegistry.displayDescriptionFor(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
        )).isEqualTo("최근 기기 등록 이벤트가 있는 고액 이체");
        assertThat(RuleV1ContractRegistry.displayDescriptionFor(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT
        )).isEqualTo("최근 보안정보 변경 시퀀스가 있는 고액 이체");
        assertThat(RuleV1ContractRegistry.displayDescriptionFor(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
        )).isEqualTo("최근 등록 수취인 이체");

        assertThatThrownBy(() ->
                RuleV1ContractRegistry.displayDescriptionFor(
                        "UNSUPPORTED_REASON"
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_REASON");
    }
}
