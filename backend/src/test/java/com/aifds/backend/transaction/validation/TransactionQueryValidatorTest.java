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
