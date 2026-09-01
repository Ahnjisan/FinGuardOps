package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.dto.FraudCaseListRequest;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseQueryValidatorTest {

    private static final String UUID_V4 =
            "a0000000-0000-4000-9000-000000000001";
    private static final String UPPERCASE_UUID_V4 =
            "A0000000-0000-4000-9000-000000000001";
    private final FraudCaseQueryValidator validator =
            new FraudCaseQueryValidator();

    @Test
    void appliesDefaultsAndParsesAllApprovedFilters() {
        FraudCaseListRequest request = new FraudCaseListRequest(
                "IN_REVIEW",
                "CONFIRMED_FRAUD",
                "analyst_ref_01",
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                "2026-08-03T00:00:00Z",
                "2026-08-04T00:00:00Z",
                UUID_V4,
                null,
                null,
                null
        );

        var criteria = validator.validate(request);

        assertThat(criteria.caseStatus()).isEqualTo(FraudCaseStatus.IN_REVIEW);
        assertThat(criteria.finalDisposition())
                .isEqualTo(FraudCaseFinalDisposition.CONFIRMED_FRAUD);
        assertThat(criteria.assigneeRef()).isEqualTo("analyst_ref_01");
        assertThat(criteria.createdAtFrom())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(criteria.createdAtTo())
                .isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(criteria.lastChangedAtFrom())
                .isEqualTo(Instant.parse("2026-08-03T00:00:00Z"));
        assertThat(criteria.lastChangedAtTo())
                .isEqualTo(Instant.parse("2026-08-04T00:00:00Z"));
        assertThat(criteria.transactionId()).isEqualTo(UUID.fromString(UUID_V4));
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.sortDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void acceptsOneSidedAndEqualRanges() {
        assertThat(validator.validate(request(
                null,
                "2026-08-01T00:00:00Z",
                null,
                null,
                null,
                null
        )).createdAtFrom()).isNotNull();
        assertThat(validator.validate(request(
                null,
                null,
                "2026-08-01T00:00:00Z",
                "2026-08-01T00:00:00Z",
                "2026-08-01T00:00:00Z",
                null
        )).lastChangedAtTo()).isNotNull();
    }

    @Test
    void rejectsUnsupportedEnumsReferencesAndDateFormatsAsFormatErrors() {
        assertFormat("caseStatus", requestWithStatus("open"));
        assertFormat("finalDisposition", requestWithDisposition("null"));
        assertFormat("assigneeRef", requestWithAssignee(" analyst "));
        assertFormat("createdAtFrom", request(
                null, "2026-08-01T09:00:00+09:00", null,
                null, null, null
        ));
    }

    @Test
    void rejectsReversedRangesAsDomainErrors() {
        assertDomain("createdAtFrom", request(
                null,
                "2026-08-02T00:00:00Z",
                "2026-08-01T00:00:00Z",
                null,
                null,
                null
        ));
        assertDomain("lastChangedAtFrom", request(
                null,
                null,
                null,
                "2026-08-02T00:00:00Z",
                "2026-08-01T00:00:00Z",
                null
        ));
    }

    @Test
    void acceptsLowercaseCanonicalUuidV4ForBothIdentifiers() {
        assertThat(validator.validateCaseId(UUID_V4))
                .isEqualTo(UUID.fromString(UUID_V4));

        var criteria = validator.validate(
                requestWithTransactionId(UUID_V4)
        );

        assertThat(criteria.transactionId()).isEqualTo(UUID.fromString(UUID_V4));
    }

    @Test
    void rejectsUppercaseUuidForBothIdentifiers() {
        assertFormatFailure(
                "caseId",
                () -> validator.validateCaseId(UPPERCASE_UUID_V4)
        );
        assertFormatFailure(
                "transactionId",
                () -> validator.validate(
                        requestWithTransactionId(UPPERCASE_UUID_V4)
                )
        );
    }

    @Test
    void rejectsNonCanonicalUuidVersionAndVariantMatrix() {
        for (String invalid : new String[]{
                "not-a-uuid",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "10000000-0000-4000-7000-000000000001",
                "a0000000000040009000000000000001",
                "{a0000000-0000-4000-9000-000000000001}",
                " a0000000-0000-4000-9000-000000000001",
                "a0000000-0000-4000-9000-000000000001 ",
                "a0000000-0000-4000-9000-00000000001",
                "g0000000-0000-4000-9000-000000000001"
        }) {
            assertFormatFailure(
                    "caseId",
                    () -> validator.validateCaseId(invalid)
            );
        }
    }

    @Test
    void separatesPageAndSortFormatFromDomainBounds() {
        assertFormat("page", request("one", null, null, null, null, null));
        assertFormat("size", request(null, null, null, null, null, "1.5"));
        assertFormat("sort", request(null, null, null, null, null,
                "createdAt,desc"));
        assertFormat("sort", request(null, null, null, null, null,
                "lastChangedAt,DESC"));
        assertDomain("page", request("-1", null, null, null, null, null));
        assertDomain("size", request(null, null, null, null, null, "101"));
    }

    @Test
    void rejectsEveryRepeatedScalarParameter() {
        FraudCaseListRequest duplicate = new FraudCaseListRequest(
                "OPEN", null, null, null, null, null, null, null,
                null, null, null,
                2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        );

        assertThatThrownBy(() -> validator.validate(duplicate))
                .isInstanceOf(FraudCaseValidationException.class)
                .extracting("field", "code", "type")
                .containsExactly(
                        "caseStatus",
                        FraudCaseQueryValidator.MULTIPLE_VALUES_NOT_ALLOWED,
                        FraudCaseValidationType.FORMAT
                );
    }

    private FraudCaseListRequest requestWithStatus(String value) {
        return new FraudCaseListRequest(
                value, null, null, null, null, null, null, null,
                null, null, null
        );
    }

    private FraudCaseListRequest requestWithDisposition(String value) {
        return new FraudCaseListRequest(
                null, value, null, null, null, null, null, null,
                null, null, null
        );
    }

    private FraudCaseListRequest requestWithAssignee(String value) {
        return new FraudCaseListRequest(
                null, null, value, null, null, null, null, null,
                null, null, null
        );
    }

    private FraudCaseListRequest requestWithTransactionId(String value) {
        return new FraudCaseListRequest(
                null, null, null, null, null, null, null, value,
                null, null, null
        );
    }

    private FraudCaseListRequest request(
            String page,
            String createdFrom,
            String createdTo,
            String changedFrom,
            String changedTo,
            String sortOrSize
    ) {
        String size = sortOrSize != null && !sortOrSize.contains(",")
                ? sortOrSize : null;
        String sort = sortOrSize != null && sortOrSize.contains(",")
                ? sortOrSize : null;
        return new FraudCaseListRequest(
                null, null, null, createdFrom, createdTo,
                changedFrom, changedTo, null, page, size, sort
        );
    }

    private void assertFormat(String field, FraudCaseListRequest request) {
        assertValidation(field, FraudCaseValidationType.FORMAT, request);
    }

    private void assertDomain(String field, FraudCaseListRequest request) {
        assertValidation(field, FraudCaseValidationType.DOMAIN, request);
    }

    private void assertFormatFailure(String field, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(FraudCaseValidationException.class)
                .extracting("field", "type")
                .containsExactly(field, FraudCaseValidationType.FORMAT);
    }

    private void assertValidation(
            String field,
            FraudCaseValidationType type,
            FraudCaseListRequest request
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(FraudCaseValidationException.class)
                .extracting("field", "type")
                .containsExactly(field, type);
    }
}
