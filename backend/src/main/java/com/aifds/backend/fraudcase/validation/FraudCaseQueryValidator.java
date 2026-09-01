package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.dto.FraudCaseListRequest;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.query.FraudCaseQueryCriteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FraudCaseQueryValidator {

    public static final String REQUEST_REQUIRED = "REQUEST_REQUIRED";
    public static final String MULTIPLE_VALUES_NOT_ALLOWED =
            "MULTIPLE_VALUES_NOT_ALLOWED";
    public static final String UNSUPPORTED_CASE_STATUS =
            "UNSUPPORTED_CASE_STATUS";
    public static final String UNSUPPORTED_FINAL_DISPOSITION =
            "UNSUPPORTED_FINAL_DISPOSITION";
    public static final String INVALID_ASSIGNEE_REF =
            "INVALID_ASSIGNEE_REF";
    public static final String INVALID_DATETIME_FORMAT =
            "INVALID_DATETIME_FORMAT";
    public static final String INVALID_DATETIME_RANGE =
            "INVALID_DATETIME_RANGE";
    public static final String INVALID_UUID_FORMAT = "INVALID_UUID_FORMAT";
    public static final String INVALID_UUID_VERSION = "INVALID_UUID_VERSION";
    public static final String INVALID_UUID_VARIANT = "INVALID_UUID_VARIANT";
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
    private static final String DEFAULT_SORT = "lastChangedAt,desc";
    private static final Pattern SIGNED_INTEGER = Pattern.compile("^-?[0-9]+$");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    public FraudCaseQueryCriteria validate(FraudCaseListRequest request) {
        if (request == null) {
            throw format("$", REQUEST_REQUIRED, "Case list request is required");
        }
        validateValueCounts(request);

        FraudCaseStatus caseStatus = parseCaseStatus(request.caseStatus());
        FraudCaseFinalDisposition finalDisposition = parseFinalDisposition(
                request.finalDisposition()
        );
        validateAssigneeRef(request.assigneeRef());

        Instant createdAtFrom = parseInstant(
                "createdAtFrom",
                request.createdAtFrom()
        );
        Instant createdAtTo = parseInstant(
                "createdAtTo",
                request.createdAtTo()
        );
        Instant lastChangedAtFrom = parseInstant(
                "lastChangedAtFrom",
                request.lastChangedAtFrom()
        );
        Instant lastChangedAtTo = parseInstant(
                "lastChangedAtTo",
                request.lastChangedAtTo()
        );
        validateRange(
                "createdAtFrom",
                createdAtFrom,
                createdAtTo
        );
        validateRange(
                "lastChangedAtFrom",
                lastChangedAtFrom,
                lastChangedAtTo
        );

        UUID transactionId = request.transactionId() == null
                ? null
                : parseUuid("transactionId", request.transactionId());
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

        return new FraudCaseQueryCriteria(
                caseStatus,
                finalDisposition,
                request.assigneeRef(),
                createdAtFrom,
                createdAtTo,
                lastChangedAtFrom,
                lastChangedAtTo,
                transactionId,
                page,
                size,
                parseSort(request.sort())
        );
    }

    public UUID validateCaseId(String rawCaseId) {
        return parseUuid("caseId", rawCaseId);
    }

    private void validateValueCounts(FraudCaseListRequest request) {
        validateSingleValue("caseStatus", request.caseStatusValueCount());
        validateSingleValue(
                "finalDisposition",
                request.finalDispositionValueCount()
        );
        validateSingleValue("assigneeRef", request.assigneeRefValueCount());
        validateSingleValue(
                "createdAtFrom",
                request.createdAtFromValueCount()
        );
        validateSingleValue("createdAtTo", request.createdAtToValueCount());
        validateSingleValue(
                "lastChangedAtFrom",
                request.lastChangedAtFromValueCount()
        );
        validateSingleValue(
                "lastChangedAtTo",
                request.lastChangedAtToValueCount()
        );
        validateSingleValue(
                "transactionId",
                request.transactionIdValueCount()
        );
        validateSingleValue("page", request.pageValueCount());
        validateSingleValue("size", request.sizeValueCount());
        validateSingleValue("sort", request.sortValueCount());
    }

    private void validateSingleValue(String field, int valueCount) {
        if (valueCount > 1) {
            throw format(
                    field,
                    MULTIPLE_VALUES_NOT_ALLOWED,
                    field + " must be provided at most once"
            );
        }
    }

    private FraudCaseStatus parseCaseStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return FraudCaseStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "caseStatus",
                    UNSUPPORTED_CASE_STATUS,
                    "caseStatus is not supported"
            );
        }
    }

    private FraudCaseFinalDisposition parseFinalDisposition(String value) {
        if (value == null) {
            return null;
        }
        try {
            return FraudCaseFinalDisposition.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw format(
                    "finalDisposition",
                    UNSUPPORTED_FINAL_DISPOSITION,
                    "finalDisposition is not supported"
            );
        }
    }

    private void validateAssigneeRef(String value) {
        if (value != null && (value.isBlank()
                || value.length() > 128
                || !value.equals(value.trim()))) {
            throw format(
                    "assigneeRef",
                    INVALID_ASSIGNEE_REF,
                    "assigneeRef must be 1 to 128 trimmed characters"
            );
        }
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

    private void validateRange(String field, Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw domain(
                    field,
                    INVALID_DATETIME_RANGE,
                    field + " must not be after its range end"
            );
        }
    }

    private UUID parseUuid(String field, String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw format(
                    field,
                    INVALID_UUID_FORMAT,
                    field + " must use the canonical UUID string format"
            );
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw format(field, INVALID_UUID_FORMAT, field + " must be a UUID");
        }
        if (uuid.version() != 4) {
            throw format(
                    field,
                    INVALID_UUID_VERSION,
                    field + " must be a UUID version 4"
            );
        }
        if (uuid.variant() != 2) {
            throw format(
                    field,
                    INVALID_UUID_VARIANT,
                    field + " must use the RFC 4122 variant"
            );
        }
        return uuid;
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
            throw format(field, errorCode, field + " must be an integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw format(field, errorCode, field + " must be an integer");
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
        String[] parts = sort.split(",", -1);
        if (parts.length != 2) {
            throw format(
                    "sort",
                    INVALID_SORT_FORMAT,
                    "sort must use field,direction format"
            );
        }
        if (!"lastChangedAt".equals(parts[0])) {
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

    private FraudCaseValidationException invalidDatetime(String field) {
        return format(
                field,
                INVALID_DATETIME_FORMAT,
                field + " must use UTC ISO-8601 Z notation"
        );
    }

    private FraudCaseValidationException format(
            String field,
            String code,
            String reason
    ) {
        return new FraudCaseValidationException(
                FraudCaseValidationType.FORMAT,
                field,
                code,
                reason
        );
    }

    private FraudCaseValidationException domain(
            String field,
            String code,
            String reason
    ) {
        return new FraudCaseValidationException(
                FraudCaseValidationType.DOMAIN,
                field,
                code,
                reason
        );
    }
}
