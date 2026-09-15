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
    private static final String UNSAFE_PAGE = "1073741824";
    private static final String UNSAFE_SIZE = "2";
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
    void rejectsPaginationOffsetsBeyondIntegerRangeWithoutReflectingInput() {
        for (FraudCaseListRequest request : new FraudCaseListRequest[]{
                unsafeRequest(
                        "OPEN", null, "credential_raw_filter",
                        null, null, null, null, null,
                        "lastChangedAt,asc"
                ),
                requestWithPagination("21474837", "100", null)
        }) {
            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOfSatisfying(
                            FraudCaseValidationException.class,
                            exception -> {
                                assertThat(exception.getType())
                                        .isEqualTo(FraudCaseValidationType.DOMAIN);
                                assertThat(exception.getField()).isEqualTo("page");
                                assertThat(exception.getCode()).isEqualTo(
                                        FraudCaseQueryValidator.PAGE_OUT_OF_RANGE
                                );
                                assertThat(exception.getReason()).isEqualTo(
                                        "page is too large for the requested size"
                                );
                                String publicError = exception.getMessage()
                                        + exception.getField()
                                        + exception.getCode()
                                        + exception.getReason();
                                assertThat(publicError).doesNotContain(
                                        request.page(),
                                        request.size(),
                                        String.valueOf(
                                                (long) Integer.parseInt(request.page())
                                                        * Integer.parseInt(request.size())
                                        ),
                                        "credential_raw_filter",
                                        "lastChangedAt,asc",
                                        "InvalidDataAccessApiUsageException",
                                        "FraudCaseQueryValidator",
                                        "PageableUtils",
                                        "Spring Data",
                                        "JPA",
                                        "SQL",
                                        "credential",
                                        "Authorization",
                                        "cookie"
                                );
                            }
                    );
        }
    }

    @Test
    void acceptsApprovedPaginationOffsetBoundaries() {
        assertPagination(null, null, 0, 20);
        assertPagination("0", "20", 0, 20);
        assertPagination("0", "1", 0, 1);
        assertPagination("2147483647", "1", Integer.MAX_VALUE, 1);
        assertPagination("21474836", "100", 21474836, 100);
    }

    @Test
    void keepsEveryLegacyQueryErrorAheadOfUnsafePaginationOffset() {
        assertRepeatedScalarPriority();

        assertLegacyValidation(
                unsafeRequest("invalid-status", null, null, null, null,
                        null, null, null, null),
                FraudCaseValidationType.FORMAT,
                "caseStatus",
                FraudCaseQueryValidator.UNSUPPORTED_CASE_STATUS,
                "caseStatus is not supported"
        );
        assertLegacyValidation(
                unsafeRequest(null, "invalid-disposition", null, null, null,
                        null, null, null, null),
                FraudCaseValidationType.FORMAT,
                "finalDisposition",
                FraudCaseQueryValidator.UNSUPPORTED_FINAL_DISPOSITION,
                "finalDisposition is not supported"
        );
        for (String assignee : new String[]{
                "", " ", " analyst ", "x".repeat(129)
        }) {
            assertLegacyValidation(
                    unsafeRequest(null, null, assignee, null, null,
                            null, null, null, null),
                    FraudCaseValidationType.FORMAT,
                    "assigneeRef",
                    FraudCaseQueryValidator.INVALID_ASSIGNEE_REF,
                    "assigneeRef must be 1 to 128 trimmed characters"
            );
        }

        assertInvalidDatetime("createdAtFrom");
        assertInvalidDatetime("createdAtTo");
        assertInvalidDatetime("lastChangedAtFrom");
        assertInvalidDatetime("lastChangedAtTo");
        assertLegacyValidation(
                unsafeRequest(null, null, null,
                        "2026-08-02T00:00:00Z",
                        "2026-08-01T00:00:00Z", null, null, null, null),
                FraudCaseValidationType.DOMAIN,
                "createdAtFrom",
                FraudCaseQueryValidator.INVALID_DATETIME_RANGE,
                "createdAtFrom must not be after its range end"
        );
        assertLegacyValidation(
                unsafeRequest(null, null, null, null, null,
                        "2026-08-02T00:00:00Z",
                        "2026-08-01T00:00:00Z", null, null),
                FraudCaseValidationType.DOMAIN,
                "lastChangedAtFrom",
                FraudCaseQueryValidator.INVALID_DATETIME_RANGE,
                "lastChangedAtFrom must not be after its range end"
        );

        assertLegacyValidation(
                unsafeRequest(null, null, null, null, null,
                        null, null, "not-a-uuid", null),
                FraudCaseValidationType.FORMAT,
                "transactionId",
                FraudCaseQueryValidator.INVALID_UUID_FORMAT,
                "transactionId must use the canonical UUID string format"
        );
        assertLegacyValidation(
                unsafeRequest(null, null, null, null, null, null, null,
                        "6ba7b810-9dad-11d1-80b4-00c04fd430c8", null),
                FraudCaseValidationType.FORMAT,
                "transactionId",
                FraudCaseQueryValidator.INVALID_UUID_VERSION,
                "transactionId must be a UUID version 4"
        );
        assertLegacyValidation(
                unsafeRequest(null, null, null, null, null, null, null,
                        "10000000-0000-4000-7000-000000000001", null),
                FraudCaseValidationType.FORMAT,
                "transactionId",
                FraudCaseQueryValidator.INVALID_UUID_VARIANT,
                "transactionId must use the RFC 4122 variant"
        );

        assertLegacyValidation(
                requestWithPagination("not-a-page", UNSAFE_SIZE, null),
                FraudCaseValidationType.FORMAT,
                "page",
                FraudCaseQueryValidator.INVALID_PAGE_FORMAT,
                "page must be an integer"
        );
        assertLegacyValidation(
                requestWithPagination("-1", UNSAFE_SIZE, null),
                FraudCaseValidationType.DOMAIN,
                "page",
                FraudCaseQueryValidator.PAGE_OUT_OF_RANGE,
                "page must be zero or greater"
        );
        assertLegacyValidation(
                requestWithPagination(UNSAFE_PAGE, "not-a-size", null),
                FraudCaseValidationType.FORMAT,
                "size",
                FraudCaseQueryValidator.INVALID_SIZE_FORMAT,
                "size must be an integer"
        );
        for (String size : new String[]{"0", "101"}) {
            assertLegacyValidation(
                    requestWithPagination(UNSAFE_PAGE, size, null),
                    FraudCaseValidationType.DOMAIN,
                    "size",
                    FraudCaseQueryValidator.SIZE_OUT_OF_RANGE,
                    "size must be between 1 and 100"
            );
        }

        assertLegacyValidation(
                requestWithPagination(UNSAFE_PAGE, UNSAFE_SIZE, "invalid-sort"),
                FraudCaseValidationType.FORMAT,
                "sort",
                FraudCaseQueryValidator.INVALID_SORT_FORMAT,
                "sort must use field,direction format"
        );
        assertLegacyValidation(
                requestWithPagination(UNSAFE_PAGE, UNSAFE_SIZE,
                        "createdAt,desc"),
                FraudCaseValidationType.FORMAT,
                "sort",
                FraudCaseQueryValidator.UNSUPPORTED_SORT_FIELD,
                "sort field is not supported"
        );
        assertLegacyValidation(
                requestWithPagination(UNSAFE_PAGE, UNSAFE_SIZE,
                        "lastChangedAt,DESC"),
                FraudCaseValidationType.FORMAT,
                "sort",
                FraudCaseQueryValidator.UNSUPPORTED_SORT_DIRECTION,
                "sort direction is not supported"
        );
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

    private FraudCaseListRequest requestWithPagination(
            String page,
            String size,
            String sort
    ) {
        return new FraudCaseListRequest(
                null, null, null, null, null, null, null, null,
                page, size, sort
        );
    }

    private FraudCaseListRequest unsafeRequest(
            String caseStatus,
            String finalDisposition,
            String assigneeRef,
            String createdAtFrom,
            String createdAtTo,
            String lastChangedAtFrom,
            String lastChangedAtTo,
            String transactionId,
            String sort
    ) {
        return new FraudCaseListRequest(
                caseStatus, finalDisposition, assigneeRef,
                createdAtFrom, createdAtTo, lastChangedAtFrom, lastChangedAtTo,
                transactionId, UNSAFE_PAGE, UNSAFE_SIZE, sort
        );
    }

    private void assertRepeatedScalarPriority() {
        String[] fields = {
                "caseStatus", "finalDisposition", "assigneeRef",
                "createdAtFrom", "createdAtTo", "lastChangedAtFrom",
                "lastChangedAtTo", "transactionId", "page", "size", "sort"
        };
        for (int duplicateIndex = 0;
             duplicateIndex < fields.length;
             duplicateIndex++) {
            int[] counts = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0};
            counts[duplicateIndex] = 2;
            FraudCaseListRequest duplicate = new FraudCaseListRequest(
                    null, null, null, null, null, null, null, null,
                    UNSAFE_PAGE, UNSAFE_SIZE, null,
                    counts[0], counts[1], counts[2], counts[3], counts[4],
                    counts[5], counts[6], counts[7], counts[8], counts[9],
                    counts[10]
            );
            assertLegacyValidation(
                    duplicate,
                    FraudCaseValidationType.FORMAT,
                    fields[duplicateIndex],
                    FraudCaseQueryValidator.MULTIPLE_VALUES_NOT_ALLOWED,
                    fields[duplicateIndex] + " must be provided at most once"
            );
        }

        FraudCaseListRequest multipleDuplicates = new FraudCaseListRequest(
                null, null, null, null, null, null, null, null,
                UNSAFE_PAGE, UNSAFE_SIZE, null,
                2, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2
        );
        assertLegacyValidation(
                multipleDuplicates,
                FraudCaseValidationType.FORMAT,
                "caseStatus",
                FraudCaseQueryValidator.MULTIPLE_VALUES_NOT_ALLOWED,
                "caseStatus must be provided at most once"
        );
    }

    private void assertInvalidDatetime(String field) {
        FraudCaseListRequest request = switch (field) {
            case "createdAtFrom" -> unsafeRequest(
                    null, null, null, "invalid-time", null,
                    null, null, null, null
            );
            case "createdAtTo" -> unsafeRequest(
                    null, null, null, null, "invalid-time",
                    null, null, null, null
            );
            case "lastChangedAtFrom" -> unsafeRequest(
                    null, null, null, null, null,
                    "invalid-time", null, null, null
            );
            case "lastChangedAtTo" -> unsafeRequest(
                    null, null, null, null, null,
                    null, "invalid-time", null, null
            );
            default -> throw new IllegalArgumentException("unsupported field");
        };
        assertLegacyValidation(
                request,
                FraudCaseValidationType.FORMAT,
                field,
                FraudCaseQueryValidator.INVALID_DATETIME_FORMAT,
                field + " must use UTC ISO-8601 Z notation"
        );
    }

    private void assertPagination(
            String page,
            String size,
            int expectedPage,
            int expectedSize
    ) {
        var criteria = validator.validate(
                requestWithPagination(page, size, null)
        );
        assertThat(criteria.page()).isEqualTo(expectedPage);
        assertThat(criteria.size()).isEqualTo(expectedSize);
    }

    private void assertLegacyValidation(
            FraudCaseListRequest request,
            FraudCaseValidationType type,
            String field,
            String code,
            String reason
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        FraudCaseValidationException.class,
                        exception -> {
                            assertThat(exception.getType()).isEqualTo(type);
                            assertThat(exception.getField()).isEqualTo(field);
                            assertThat(exception.getCode()).isEqualTo(code);
                            assertThat(exception.getReason()).isEqualTo(reason);
                            assertThat(exception.getMessage()).doesNotContain(
                                    UNSAFE_PAGE,
                                    UNSAFE_SIZE,
                                    "2147483648",
                                    "InvalidDataAccessApiUsageException",
                                    "SQL",
                                    "credential",
                                    "Authorization",
                                    "cookie"
                            );
                        }
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
