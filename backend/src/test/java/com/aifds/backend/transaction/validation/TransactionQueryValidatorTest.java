package com.aifds.backend.transaction.validation;

import com.aifds.backend.transaction.dto.TransactionListRequest;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.query.TransactionQueryCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionQueryValidatorTest {

    private final TransactionQueryValidator validator =
            new TransactionQueryValidator();

    @Test
    void appliesApprovedDefaults() {
        TransactionQueryCriteria criteria = validator.validate(request());

        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.sortDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(criteria.occurredAtFrom()).isNull();
        assertThat(criteria.occurredAtTo()).isNull();
    }

    @Test
    void parsesEverySupportedFilterWithoutNormalizingReferences() {
        TransactionListRequest request = new TransactionListRequest(
                "2026-07-23T00:00:00Z",
                "2026-07-24T00:00:00Z",
                "ACCOUNT_TRANSFER",
                "ANALYZED",
                " CustomerRef ",
                " AccountRef ",
                "2",
                "100",
                "occurredAt,asc"
        );

        TransactionQueryCriteria criteria = validator.validate(request);

        assertThat(criteria.occurredAtFrom())
                .isEqualTo(Instant.parse("2026-07-23T00:00:00Z"));
        assertThat(criteria.occurredAtTo())
                .isEqualTo(Instant.parse("2026-07-24T00:00:00Z"));
        assertThat(criteria.transactionType())
                .isEqualTo(TransactionType.ACCOUNT_TRANSFER);
        assertThat(criteria.processingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(criteria.externalCustomerRef())
                .isEqualTo(" CustomerRef ");
        assertThat(criteria.accountRef()).isEqualTo(" AccountRef ");
        assertThat(criteria.page()).isEqualTo(2);
        assertThat(criteria.size()).isEqualTo(100);
        assertThat(criteria.sortDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void allowsEitherDateBoundaryAndEqualEmptyRange() {
        assertThat(validator.validate(requestWithDates(
                "2026-07-23T00:00:00Z",
                null
        )).occurredAtTo()).isNull();
        assertThat(validator.validate(requestWithDates(
                null,
                "2026-07-24T00:00:00Z"
        )).occurredAtFrom()).isNull();

        TransactionQueryCriteria equal = validator.validate(requestWithDates(
                "2026-07-23T00:00:00Z",
                "2026-07-23T00:00:00Z"
        ));
        assertThat(equal.occurredAtFrom()).isEqualTo(equal.occurredAtTo());
    }

    @Test
    void rejectsFromAfterToAsDomainValidation() {
        assertValidation(
                requestWithDates(
                        "2026-07-24T00:00:00Z",
                        "2026-07-23T00:00:00Z"
                ),
                TransactionValidationType.DOMAIN,
                "occurredAtFrom",
                TransactionQueryValidator.INVALID_OCCURRED_AT_RANGE
        );
    }

    @Test
    void rejectsNonUtcAndMalformedDatesAsFormatValidation() {
        for (String value : new String[]{
                "2026-07-23T09:00:00+09:00",
                "2026-07-23",
                "",
                "not-a-date"
        }) {
            assertValidation(
                    requestWithDates(value, null),
                    TransactionValidationType.FORMAT,
                    "occurredAtFrom",
                    TransactionQueryValidator.INVALID_DATETIME_FORMAT
            );
        }
    }

    @Test
    void rejectsUnsupportedAndMultipleEnumsWithoutChangingCase() {
        assertValidation(
                withTransactionType("account_transfer"),
                TransactionValidationType.FORMAT,
                "transactionType",
                TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE
        );
        assertValidation(
                withTransactionType(
                        "ACCOUNT_TRANSFER,ATM_WITHDRAWAL"
                ),
                TransactionValidationType.FORMAT,
                "transactionType",
                TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE
        );
        assertValidation(
                withProcessingStatus("received"),
                TransactionValidationType.FORMAT,
                "processingStatus",
                TransactionQueryValidator.UNSUPPORTED_PROCESSING_STATUS
        );
    }

    @Test
    void rejectsOnlyEmptyOrBlankReferences() {
        for (String value : new String[]{"", " ", "\t"}) {
            assertValidation(
                    withExternalCustomerRef(value),
                    TransactionValidationType.FORMAT,
                    "externalCustomerRef",
                    TransactionQueryValidator.INVALID_REFERENCE_VALUE
            );
            assertValidation(
                    withAccountRef(value),
                    TransactionValidationType.FORMAT,
                    "accountRef",
                    TransactionQueryValidator.INVALID_REFERENCE_VALUE
            );
        }
    }

    @Test
    void distinguishesPageAndSizeFormatFromRangeErrors() {
        assertValidation(
                withPage("one"),
                TransactionValidationType.FORMAT,
                "page",
                TransactionQueryValidator.INVALID_PAGE_FORMAT
        );
        assertValidation(
                withSize("1.5"),
                TransactionValidationType.FORMAT,
                "size",
                TransactionQueryValidator.INVALID_SIZE_FORMAT
        );
        assertValidation(
                withPage("-1"),
                TransactionValidationType.DOMAIN,
                "page",
                TransactionQueryValidator.PAGE_OUT_OF_RANGE
        );
        for (String size : new String[]{"0", "101"}) {
            assertValidation(
                    withSize(size),
                    TransactionValidationType.DOMAIN,
                    "size",
                    TransactionQueryValidator.SIZE_OUT_OF_RANGE
            );
        }
    }

    @Test
    void rejectsPaginationOffsetBeyondIntegerRange() {
        TransactionListRequest request = new TransactionListRequest(
                null, null, null, null, null, null,
                "1073741824", "2", null
        );

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType())
                                    .isEqualTo(TransactionValidationType.DOMAIN);
                            assertThat(exception.getField()).isEqualTo("page");
                            assertThat(exception.getCode()).isEqualTo(
                                    TransactionQueryValidator.PAGE_OUT_OF_RANGE
                            );
                            assertThat(exception.getReason()).isEqualTo(
                                    "page is too large for the requested size"
                            );
                            assertThat(exception.getMessage()).doesNotContain(
                                    "1073741824",
                                    "2",
                                    "2147483648"
                            );
                        }
                );
    }

    @Test
    void acceptsPaginationOffsetsAtApprovedBoundaries() {
        assertPagination("2147483647", "1", 2147483647, 1);
        assertPagination("21474836", "100", 21474836, 100);
        assertPagination("0", "100", 0, 100);
        assertPagination("10", "1", 10, 1);
    }

    @Test
    void rejectsFirstPaginationOffsetBeyondSizeOneHundredBoundary() {
        assertOffsetValidation(new TransactionListRequest(
                null, null, null, null, null, null,
                "21474837", "100", null
        ));
    }

    @Test
    void appliesSamePaginationOffsetRuleWithFilterAndSort() {
        assertOffsetValidation(new TransactionListRequest(
                null, null, null, null, "customer_filter", null,
                "1073741824", "2", null
        ));
        assertOffsetValidation(new TransactionListRequest(
                null, null, null, null, null, null,
                "1073741824", "2", "occurredAt,asc"
        ));
    }

    @Test
    void keepsIndividualPageAndSizeErrorsAheadOfOffsetValidation() {
        assertNotOffsetValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "not-a-page", "2", null
                ),
                "page",
                TransactionQueryValidator.INVALID_PAGE_FORMAT
        );
        assertNotOffsetValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "-1", "2", null
                ),
                "page",
                TransactionQueryValidator.PAGE_OUT_OF_RANGE
        );
        assertNotOffsetValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "1073741824", "not-a-size", null
                ),
                "size",
                TransactionQueryValidator.INVALID_SIZE_FORMAT
        );
        assertNotOffsetValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "21474837", "101", null
                ),
                "size",
                TransactionQueryValidator.SIZE_OUT_OF_RANGE
        );
    }

    @Test
    void keepsMalformedSortAheadOfUnsafePaginationOffset() {
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "1073741824", "2", "invalid-sort"
                ),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.INVALID_SORT_FORMAT,
                "sort must use field,direction format",
                "invalid-sort"
        );
    }

    @Test
    void keepsEveryLegacyQueryErrorAheadOfUnsafePaginationOffset() {
        assertLegacyValidation(
                new TransactionListRequest(
                        "invalid-time", null, null, null, null, null,
                        "1073741824", "2", null
                ),
                TransactionValidationType.FORMAT,
                "occurredAtFrom",
                TransactionQueryValidator.INVALID_DATETIME_FORMAT,
                "occurredAtFrom must use UTC ISO-8601 Z notation",
                "invalid-time"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        "2026-07-24T00:00:00Z",
                        "2026-07-23T00:00:00Z",
                        null, null, null, null,
                        "1073741824", "2", null
                ),
                TransactionValidationType.DOMAIN,
                "occurredAtFrom",
                TransactionQueryValidator.INVALID_OCCURRED_AT_RANGE,
                "occurredAtFrom must not be after occurredAtTo",
                "2026-07-24T00:00:00Z"
        );
        assertLegacyValidation(
                requestWithCounts("ACCOUNT_TRANSFER", null, null, 2, 0, 0),
                TransactionValidationType.FORMAT,
                "transactionType",
                TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE,
                "transactionType must be provided at most once",
                "ACCOUNT_TRANSFER"
        );
        assertLegacyValidation(
                requestWithCounts(null, "RECEIVED", null, 0, 2, 0),
                TransactionValidationType.FORMAT,
                "processingStatus",
                TransactionQueryValidator.UNSUPPORTED_PROCESSING_STATUS,
                "processingStatus must be provided at most once",
                "RECEIVED"
        );
        assertLegacyValidation(
                requestWithCounts(null, null, "occurredAt,desc", 0, 0, 2),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.INVALID_SORT_FORMAT,
                "sort must be provided at most once",
                "occurredAt,desc"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, "invalid-type", null, null, null,
                        "1073741824", "2", null
                ),
                TransactionValidationType.FORMAT,
                "transactionType",
                TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE,
                "transactionType is not supported",
                "invalid-type"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, "invalid-status", null, null,
                        "1073741824", "2", null
                ),
                TransactionValidationType.FORMAT,
                "processingStatus",
                TransactionQueryValidator.UNSUPPORTED_PROCESSING_STATUS,
                "processingStatus is not supported",
                "invalid-status"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, null, " ", null,
                        "1073741824", "2", null
                ),
                TransactionValidationType.FORMAT,
                "externalCustomerRef",
                TransactionQueryValidator.INVALID_REFERENCE_VALUE,
                "externalCustomerRef must not be blank",
                "1073741824"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, null, null, "\t",
                        "1073741824", "2", null
                ),
                TransactionValidationType.FORMAT,
                "accountRef",
                TransactionQueryValidator.INVALID_REFERENCE_VALUE,
                "accountRef must not be blank",
                "1073741824"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "1073741824", "2", ""
                ),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.INVALID_SORT_FORMAT,
                "sort must use field,direction format",
                "1073741824"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "1073741824", "2", "createdAt,desc"
                ),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.UNSUPPORTED_SORT_FIELD,
                "sort field is not supported",
                "createdAt,desc"
        );
        assertLegacyValidation(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        "1073741824", "2", "occurredAt,DESC"
                ),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.UNSUPPORTED_SORT_DIRECTION,
                "sort direction is not supported",
                "occurredAt,DESC"
        );
    }

    @Test
    void acceptsOnlyOneApprovedSortFieldAndLowercaseDirection() {
        assertThat(validator.validate(withSort("occurredAt,asc"))
                .sortDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(validator.validate(withSort("occurredAt,desc"))
                .sortDirection()).isEqualTo(Sort.Direction.DESC);

        assertValidation(
                withSort("createdAt,desc"),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.UNSUPPORTED_SORT_FIELD
        );
        assertValidation(
                withSort("occurredAt,DESC"),
                TransactionValidationType.FORMAT,
                "sort",
                TransactionQueryValidator.UNSUPPORTED_SORT_DIRECTION
        );
        for (String sort : new String[]{
                "",
                "occurredAt",
                "occurredAt,desc,id,desc",
                "occurredAt,desc,occurredAt,asc"
        }) {
            assertValidation(
                    withSort(sort),
                    TransactionValidationType.FORMAT,
                    "sort",
                    TransactionQueryValidator.INVALID_SORT_FORMAT
            );
        }
    }

    @Test
    void validatesCanonicalVersionFourRfc4122TransactionIds() {
        UUID id = validator.validateTransactionId(
                "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
        );
        assertThat(id.version()).isEqualTo(4);
        assertThat(id.variant()).isEqualTo(2);

        assertTransactionIdError(
                "not-a-uuid",
                TransactionRequestValidator.INVALID_UUID_FORMAT
        );
        assertTransactionIdError(
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                TransactionRequestValidator.INVALID_UUID_VERSION
        );
        assertTransactionIdError(
                "2f4c0a4e-8a9d-4c2f-7a1b-7d6e5f430001",
                TransactionRequestValidator.INVALID_UUID_VARIANT
        );
    }

    private void assertTransactionIdError(String value, String code) {
        assertThatThrownBy(() -> validator.validateTransactionId(value))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType())
                                    .isEqualTo(
                                            TransactionValidationType.FORMAT
                                    );
                            assertThat(exception.getField())
                                    .isEqualTo("transactionId");
                            assertThat(exception.getCode()).isEqualTo(code);
                        }
                );
    }

    private void assertValidation(
            TransactionListRequest request,
            TransactionValidationType type,
            String field,
            String code
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType()).isEqualTo(type);
                            assertThat(exception.getField()).isEqualTo(field);
                            assertThat(exception.getCode()).isEqualTo(code);
                        }
                );
    }

    private void assertPagination(
            String page,
            String size,
            int expectedPage,
            int expectedSize
    ) {
        TransactionQueryCriteria criteria = validator.validate(
                new TransactionListRequest(
                        null, null, null, null, null, null,
                        page, size, null
                )
        );

        assertThat(criteria.page()).isEqualTo(expectedPage);
        assertThat(criteria.size()).isEqualTo(expectedSize);
    }

    private void assertOffsetValidation(TransactionListRequest request) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType())
                                    .isEqualTo(TransactionValidationType.DOMAIN);
                            assertThat(exception.getField()).isEqualTo("page");
                            assertThat(exception.getCode()).isEqualTo(
                                    TransactionQueryValidator.PAGE_OUT_OF_RANGE
                            );
                            assertThat(exception.getReason()).isEqualTo(
                                    "page is too large for the requested size"
                            );
                        }
                );
    }

    private void assertNotOffsetValidation(
            TransactionListRequest request,
            String expectedField,
            String expectedCode
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getField())
                                    .isEqualTo(expectedField);
                            assertThat(exception.getCode())
                                    .isEqualTo(expectedCode);
                            assertThat(exception.getReason()).isNotEqualTo(
                                    "page is too large for the requested size"
                            );
                        }
                );
    }

    private void assertLegacyValidation(
            TransactionListRequest request,
            TransactionValidationType expectedType,
            String expectedField,
            String expectedCode,
            String expectedReason,
            String rawValue
    ) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOfSatisfying(
                        TransactionValidationException.class,
                        exception -> {
                            assertThat(exception.getType())
                                    .isEqualTo(expectedType);
                            assertThat(exception.getField())
                                    .isEqualTo(expectedField);
                            assertThat(exception.getCode())
                                    .isEqualTo(expectedCode)
                                    .isNotEqualTo(
                                            TransactionQueryValidator
                                                    .PAGE_OUT_OF_RANGE
                                    );
                            assertThat(exception.getReason())
                                    .isEqualTo(expectedReason)
                                    .doesNotContain(
                                            rawValue,
                                            "1073741824",
                                            "2147483648"
                                    );
                        }
                );
    }

    private TransactionListRequest requestWithCounts(
            String transactionType,
            String processingStatus,
            String sort,
            int transactionTypeCount,
            int processingStatusCount,
            int sortCount
    ) {
        return new TransactionListRequest(
                null, null, transactionType, processingStatus, null, null,
                "1073741824", "2", sort,
                transactionTypeCount, processingStatusCount, sortCount
        );
    }

    private TransactionListRequest request() {
        return new TransactionListRequest(
                null, null, null, null, null, null, null, null, null
        );
    }

    private TransactionListRequest requestWithDates(String from, String to) {
        return new TransactionListRequest(
                from, to, null, null, null, null, null, null, null
        );
    }

    private TransactionListRequest withTransactionType(String value) {
        return new TransactionListRequest(
                null, null, value, null, null, null, null, null, null
        );
    }

    private TransactionListRequest withProcessingStatus(String value) {
        return new TransactionListRequest(
                null, null, null, value, null, null, null, null, null
        );
    }

    private TransactionListRequest withExternalCustomerRef(String value) {
        return new TransactionListRequest(
                null, null, null, null, value, null, null, null, null
        );
    }

    private TransactionListRequest withAccountRef(String value) {
        return new TransactionListRequest(
                null, null, null, null, null, value, null, null, null
        );
    }

    private TransactionListRequest withPage(String value) {
        return new TransactionListRequest(
                null, null, null, null, null, null, value, null, null
        );
    }

    private TransactionListRequest withSize(String value) {
        return new TransactionListRequest(
                null, null, null, null, null, null, null, value, null
        );
    }

    private TransactionListRequest withSort(String value) {
        return new TransactionListRequest(
                null, null, null, null, null, null, null, null, value
        );
    }
}
