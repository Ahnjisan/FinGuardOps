package com.aifds.backend.transaction.validation;

import com.aifds.backend.transaction.dto.TransactionListRequest;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.query.TransactionQueryCriteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class TransactionQueryValidator {

    public static final String INVALID_DATETIME_FORMAT =
            "INVALID_DATETIME_FORMAT";
    public static final String INVALID_OCCURRED_AT_RANGE =
            "INVALID_OCCURRED_AT_RANGE";
    public static final String UNSUPPORTED_PROCESSING_STATUS =
            "UNSUPPORTED_PROCESSING_STATUS";
    public static final String INVALID_REFERENCE_VALUE =
            "INVALID_REFERENCE_VALUE";
    public static final String INVALID_PAGE_FORMAT = "INVALID_PAGE_FORMAT";
    public static final String INVALID_SIZE_FORMAT = "INVALID_SIZE_FORMAT";
    public static final String PAGE_OUT_OF_RANGE = "PAGE_OUT_OF_RANGE";
    public static final String SIZE_OUT_OF_RANGE = "SIZE_OUT_OF_RANGE";
    public static final String INVALID_SORT_FORMAT = "INVALID_SORT_FORMAT";
    public static final String UNSUPPORTED_SORT_FIELD =
            "UNSUPPORTED_SORT_FIELD";
    public static final String UNSUPPORTED_SORT_DIRECTION =
            "UNSUPPORTED_SORT_DIRECTION";

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final String DEFAULT_SORT = "occurredAt,desc";
    private static final Pattern SIGNED_INTEGER = Pattern.compile("^-?[0-9]+$");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
                    + "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$"
    );

    public TransactionQueryCriteria validate(TransactionListRequest request) {
        if (request == null) {
            throw format(
                    "$",
                    TransactionRequestValidator.REQUEST_REQUIRED,
                    "Transaction list request is required"
            );
        }

        Instant occurredAtFrom = parseInstant(
                "occurredAtFrom",
                request.occurredAtFrom()
        );
        Instant occurredAtTo = parseInstant(
                "occurredAtTo",
                request.occurredAtTo()
        );
        validateOccurredAtRange(occurredAtFrom, occurredAtTo);

        validateSingleValue(
                "transactionType",
                request.transactionTypeValueCount(),
                TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE
        );
        validateSingleValue(
                "processingStatus",
                request.processingStatusValueCount(),
                UNSUPPORTED_PROCESSING_STATUS
        );
        validateSingleValue(
                "sort",
                request.sortValueCount(),
                INVALID_SORT_FORMAT
        );

        TransactionType transactionType =
                parseTransactionType(request.transactionType());
        TransactionProcessingStatus processingStatus =
                parseProcessingStatus(request.processingStatus());
        validateReference(
                "externalCustomerRef",
                request.externalCustomerRef()
        );
        validateReference("accountRef", request.accountRef());

        int page = parseInteger(
                "page",
                request.page(),
                DEFAULT_PAGE,
                INVALID_PAGE_FORMAT
        );
        int size = parseInteger(
                "size",
                request.size(),
                DEFAULT_SIZE,
                INVALID_SIZE_FORMAT
        );
        validatePageAndSize(page, size);

        Sort.Direction direction = parseSort(request.sort());
        return new TransactionQueryCriteria(
                occurredAtFrom,
                occurredAtTo,
                transactionType,
                processingStatus,
                request.externalCustomerRef(),
                request.accountRef(),
                page,
                size,
                direction
        );
    }

    public UUID validateTransactionId(String rawTransactionId) {
        if (rawTransactionId == null
                || !CANONICAL_UUID.matcher(rawTransactionId).matches()) {
            throw format(
                    "transactionId",
                    TransactionRequestValidator.INVALID_UUID_FORMAT,
                    "transactionId must use the canonical UUID string format"
            );
        }

        UUID transactionId;
        try {
            transactionId = UUID.fromString(rawTransactionId);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "transactionId",
                    TransactionRequestValidator.INVALID_UUID_FORMAT,
                    "transactionId must be a UUID"
            );
        }
        if (transactionId.version() != 4) {
            throw format(
                    "transactionId",
                    TransactionRequestValidator.INVALID_UUID_VERSION,
                    "transactionId must be a UUID version 4"
            );
        }
        if (transactionId.variant() != 2) {
            throw format(
                    "transactionId",
                    TransactionRequestValidator.INVALID_UUID_VARIANT,
                    "transactionId must use the RFC 4122 variant"
            );
        }
        return transactionId;
    }

    private Instant parseInstant(String field, String value) {
        if (value == null) {
            return null;
        }
        if (!value.endsWith("Z")) {
            throw invalidDatetime(field);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException exception) {
            throw invalidDatetime(field);
        }
    }

    private void validateOccurredAtRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw domain(
                    "occurredAtFrom",
                    INVALID_OCCURRED_AT_RANGE,
                    "occurredAtFrom must not be after occurredAtTo"
            );
        }
    }

    private TransactionType parseTransactionType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TransactionType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "transactionType",
                    TransactionRequestValidator.UNSUPPORTED_TRANSACTION_TYPE,
                    "transactionType is not supported"
            );
        }
    }

    private TransactionProcessingStatus parseProcessingStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TransactionProcessingStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "processingStatus",
                    UNSUPPORTED_PROCESSING_STATUS,
                    "processingStatus is not supported"
            );
        }
    }

    private void validateReference(String field, String value) {
        if (value != null && value.isBlank()) {
            throw format(
                    field,
                    INVALID_REFERENCE_VALUE,
                    field + " must not be blank"
            );
        }
    }

    private void validateSingleValue(
            String field,
            int valueCount,
            String errorCode
    ) {
        if (valueCount > 1) {
            throw format(
                    field,
                    errorCode,
                    field + " must be provided at most once"
            );
        }
    }

    private int parseInteger(
            String field,
            String value,
            int defaultValue,
            String errorCode
    ) {
        if (value == null) {
            return defaultValue;
        }
        if (!SIGNED_INTEGER.matcher(value).matches()) {
            throw format(
                    field,
                    errorCode,
                    field + " must be an integer"
            );
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw format(
                    field,
                    errorCode,
                    field + " must be an integer"
            );
        }
    }

    private void validatePageAndSize(int page, int size) {
        if (page < 0) {
            throw domain(
                    "page",
                    PAGE_OUT_OF_RANGE,
                    "page must be zero or greater"
            );
        }
        if (size < 1 || size > MAX_SIZE) {
            throw domain(
                    "size",
                    SIZE_OUT_OF_RANGE,
                    "size must be between 1 and 100"
            );
        }
    }

    private Sort.Direction parseSort(String rawSort) {
        String sort = rawSort == null ? DEFAULT_SORT : rawSort;
        if (sort.isEmpty()) {
            throw format(
                    "sort",
                    INVALID_SORT_FORMAT,
                    "sort must use field,direction format"
            );
        }
        String[] parts = sort.split(",", -1);
        if (parts.length != 2) {
            throw format(
                    "sort",
                    INVALID_SORT_FORMAT,
                    "sort must use field,direction format"
            );
        }
        if (!"occurredAt".equals(parts[0])) {
            throw format(
                    "sort",
                    UNSUPPORTED_SORT_FIELD,
                    "sort field is not supported"
            );
        }
        return switch (parts[1]) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw format(
                    "sort",
                    UNSUPPORTED_SORT_DIRECTION,
                    "sort direction is not supported"
            );
        };
    }

    private TransactionValidationException invalidDatetime(String field) {
        return format(
                field,
                INVALID_DATETIME_FORMAT,
                field + " must use UTC ISO-8601 Z notation"
        );
    }

    private TransactionValidationException format(
            String field,
            String code,
            String reason
    ) {
        return new TransactionValidationException(
                TransactionValidationType.FORMAT,
                field,
                code,
                reason
        );
    }

    private TransactionValidationException domain(
            String field,
            String code,
            String reason
    ) {
        return new TransactionValidationException(
                TransactionValidationType.DOMAIN,
                field,
                code,
                reason
        );
    }
}
