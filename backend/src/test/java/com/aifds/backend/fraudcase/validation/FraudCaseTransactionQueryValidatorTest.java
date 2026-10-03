package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.query.FraudCaseTransactionQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseTransactionQueryValidatorTest {
    private static final String CASE_ID = "20000000-0000-4000-9000-000000000003";
    private final FraudCaseTransactionQueryValidator validator = new FraudCaseTransactionQueryValidator();

    @Test
    void defaultsAndInclusiveOffsetBoundary() {
        var defaults = validator.validate(new FraudCaseTransactionQuery.Request(CASE_ID, Map.of()));
        assertThat(defaults.page()).isZero();
        assertThat(defaults.size()).isEqualTo(20);
        var limit = validator.validate(new FraudCaseTransactionQuery.Request(CASE_ID,
                Map.of("page", List.of("2147483647"), "size", List.of("1"))));
        assertThat(limit.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limit.size()).isEqualTo(1);
    }

    @Test
    void offsetOverflowIsPageDomainError() {
        assertThatThrownBy(() -> validator.validate(new FraudCaseTransactionQuery.Request(CASE_ID,
                Map.of("page", List.of("1073741824"), "size", List.of("2")))))
                .isInstanceOfSatisfying(FraudCaseValidationException.class, error -> {
                    assertThat(error.getType()).isEqualTo(FraudCaseValidationType.DOMAIN);
                    assertThat(error.getField()).isEqualTo("page");
                    assertThat(error.getCode()).isEqualTo("PAGE_OUT_OF_RANGE");
                });
    }

    @Test
    void rejectsRepeatedAndUnsupportedParameters() {
        assertThatThrownBy(() -> validator.validate(new FraudCaseTransactionQuery.Request(CASE_ID,
                Map.of("page", List.of("0", "1")))))
                .isInstanceOfSatisfying(FraudCaseValidationException.class, error ->
                        assertThat(error.getCode()).isEqualTo("MULTIPLE_VALUES_NOT_ALLOWED"));
        assertThatThrownBy(() -> validator.validate(new FraudCaseTransactionQuery.Request(CASE_ID,
                Map.of("sort", List.of("transactionId,asc")))))
                .isInstanceOfSatisfying(FraudCaseValidationException.class, error ->
                        assertThat(error.getCode()).isEqualTo("UNSUPPORTED_QUERY_PARAMETER"));
    }
}
