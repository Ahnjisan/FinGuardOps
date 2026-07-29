package com.aifds.backend.transaction.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionQueryMapperTest {

    private final TransactionQueryMapper mapper =
            new TransactionQueryMapper();

    @Test
    void removesTrailingZerosWithoutRoundingOrScientificNotation() {
        assertThat(mapper.amountToString(new BigDecimal("1250000.0000")))
                .isEqualTo("1250000");
        assertThat(mapper.amountToString(new BigDecimal("1250.5000")))
                .isEqualTo("1250.5");
        assertThat(mapper.amountToString(new BigDecimal("0.00000010")))
                .isEqualTo("0.0000001");
        assertThat(mapper.amountToString(new BigDecimal("1000000000000000")))
                .isEqualTo("1000000000000000");
    }
}
