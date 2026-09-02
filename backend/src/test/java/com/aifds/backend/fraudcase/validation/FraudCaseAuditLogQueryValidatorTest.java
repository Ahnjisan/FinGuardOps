package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.query.FraudCaseAuditLogQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseAuditLogQueryValidatorTest {

    private static final String CASE_ID =
            "10000000-0000-4000-9000-000000000001";

    private final FraudCaseAuditLogQueryValidator validator =
            new FraudCaseAuditLogQueryValidator();

    @Test
    void acceptsCanonicalUuidDefaultsAndApprovedBounds() {
        FraudCaseAuditLogQuery defaults = validator.validate(request(Map.of()));
        assertThat(defaults.caseId().toString()).isEqualTo(CASE_ID);
        assertThat(defaults.page()).isZero();
        assertThat(defaults.size()).isEqualTo(20);
        assertThat(defaults.sortDirection()).isEqualTo(Sort.Direction.DESC);

        FraudCaseAuditLogQuery bounded = validator.validate(request(Map.of(
                "page", List.of("0"),
                "size", List.of("100"),
                "sort", List.of("changedAt,asc")
        )));
        assertThat(bounded.size()).isEqualTo(100);
        assertThat(bounded.sortDirection()).isEqualTo(Sort.Direction.ASC);

        FraudCaseAuditLogQuery maximumPage = validator.validate(request(
                Map.of("page", List.of("2147483647"))
        ));
        assertThat(maximumPage.page()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10000000-0000-4000-9000-00000000000A",
            "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
            "10000000-0000-4000-7000-000000000001",
            " 10000000-0000-4000-9000-000000000001",
            "10000000-0000-4000-9000-000000000001 ",
            "10000000000040009000000000000001",
            "10000000-0000-4000-9000-00000000001",
            "10000000-0000-4000-9000-00000000000g",
            "１０００００００-0000-4000-9000-000000000001",
            "10000000-0000-4000-9000-000000000001\n",
            "10000000-0000-4000-9000-000000000001\t"
    })
    void rejectsNonCanonicalOrNonV4CaseIdsWithoutCoercion(String value) {
        assertThatThrownBy(() -> validator.validate(newRequest(value, Map.of())))
                .isInstanceOf(FraudCaseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"one", "1.0", "+1", " 1", "1 ", "１", "", "2147483648"})
    void rejectsInvalidPageFormats(String value) {
        assertInvalid("page", value, FraudCaseValidationType.FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"one", "1.0", "+1", " 1", "1 ", "１", "", "2147483648"})
    void rejectsInvalidSizeFormats(String value) {
        assertInvalid("size", value, FraudCaseValidationType.FORMAT);
    }

    @Test
    void rejectsSemanticPageAndSizeBoundsAsDomainErrors() {
        assertInvalid("page", "-1", FraudCaseValidationType.DOMAIN);
        assertInvalid("size", "0", FraudCaseValidationType.DOMAIN);
        assertInvalid("size", "101", FraudCaseValidationType.DOMAIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "id,asc", "changedAt,up", "changedAt", "changedAt,asc,extra",
            "changedAt,ASC", " changedAt,asc", "changedAt,asc ", ""
    })
    void rejectsUnapprovedSortFormsWithoutCoercion(String value) {
        assertInvalid("sort", value, FraudCaseValidationType.FORMAT);
    }

    @Test
    void rejectsUnknownAndEveryDuplicatedScalar() {
        assertThatThrownBy(() -> validator.validate(request(Map.of(
                "unknown", List.of("secret")
        )))).isInstanceOf(FraudCaseValidationException.class)
                .extracting("code")
                .isEqualTo(FraudCaseAuditLogQueryValidator
                        .UNSUPPORTED_QUERY_PARAMETER);

        for (String field : List.of("page", "size", "sort")) {
            assertThatThrownBy(() -> validator.validate(request(Map.of(
                    field, List.of("1", "2")
            )))).isInstanceOf(FraudCaseValidationException.class)
                    .extracting("code")
                    .isEqualTo(FraudCaseAuditLogQueryValidator
                            .MULTIPLE_VALUES_NOT_ALLOWED);
        }
    }

    private void assertInvalid(
            String field,
            String value,
            FraudCaseValidationType type
    ) {
        assertThatThrownBy(() -> validator.validate(request(Map.of(
                field, List.of(value)
        )))).isInstanceOf(FraudCaseValidationException.class)
                .satisfies(exception -> assertThat(
                        ((FraudCaseValidationException) exception).getType()
                ).isEqualTo(type));
    }

    private FraudCaseAuditLogQuery.Request request(
            Map<String, List<String>> parameters
    ) {
        return newRequest(CASE_ID, parameters);
    }

    private FraudCaseAuditLogQuery.Request newRequest(
            String caseId,
            Map<String, List<String>> parameters
    ) {
        return new FraudCaseAuditLogQuery.Request(caseId, parameters);
    }
}
