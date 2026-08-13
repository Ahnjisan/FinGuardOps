package com.aifds.backend.rule.contract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRuleSetVersionCalculatorTest {

    private final CanonicalRuleSetVersionCalculator calculator =
            new CanonicalRuleSetVersionCalculator();

    @Test
    void matchesPythonGoldenVectorExactly() {
        List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities =
                List.of(identity(4), identity(1), identity(3));

        assertThat(calculator.calculate(identities)).isEqualTo(
                "085edb92debd4e80d8472f77fab507d846810c668268ee34d8ee97ec2c917b26"
        );
    }

    @Test
    void canonicalInputUsesTabsUtf8AndTrailingLineFeed() {
        byte[] input = calculator.canonicalInput(
                List.of(identity(4), identity(1), identity(3))
        );

        assertThat(new String(input, StandardCharsets.UTF_8)).isEqualTo(
                "rule-plan-v1\n"
                        + "1\t20000000-0000-4000-8000-000000000001\t"
                        + "TRANSFER_ABSOLUTE_HIGH_AMOUNT\tR001\t1\n"
                        + "2\t20000000-0000-4000-8000-000000000003\t"
                        + "RECENT_SECURITY_CHANGE_HIGH_AMOUNT\tR003\t1\n"
                        + "3\t20000000-0000-4000-8000-000000000004\t"
                        + "RECENT_BENEFICIARY_TRANSFER\tR004\t1\n"
        );
        assertThat(input[0]).isEqualTo((byte) 'r');
    }

    @Test
    void inputOrderDoesNotChangeHash() {
        assertThat(calculator.calculate(
                List.of(identity(1), identity(3), identity(4))
        )).isEqualTo(calculator.calculate(
                List.of(identity(4), identity(1), identity(3))
        ));
    }

    private RuleV1ExecutionPlanRegistry.RuleVersionIdentity identity(
            int ruleNumber
    ) {
        return new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                UUID.fromString(
                        "10000000-0000-4000-8000-00000000000" + ruleNumber
                ),
                UUID.fromString(
                        "20000000-0000-4000-8000-00000000000" + ruleNumber
                ),
                switch (ruleNumber) {
                    case 1 -> RuleV1ContractRegistry
                            .TRANSFER_ABSOLUTE_HIGH_AMOUNT;
                    case 3 -> RuleV1ContractRegistry
                            .RECENT_SECURITY_CHANGE_HIGH_AMOUNT;
                    case 4 -> RuleV1ContractRegistry
                            .RECENT_BENEFICIARY_TRANSFER;
                    default -> throw new IllegalArgumentException();
                },
                1
        );
    }
}
