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
}
