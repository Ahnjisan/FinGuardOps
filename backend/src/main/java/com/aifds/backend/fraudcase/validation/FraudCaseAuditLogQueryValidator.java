package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.query.FraudCaseAuditLogQuery;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FraudCaseAuditLogQueryValidator {

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
    public static final String UNSUPPORTED_QUERY_PARAMETER =
            "UNSUPPORTED_QUERY_PARAMETER";
    public static final String MULTIPLE_VALUES_NOT_ALLOWED =
            "MULTIPLE_VALUES_NOT_ALLOWED";

    private static final Set<String> ALLOWED_QUERY_PARAMETERS = Set.of(
            "page", "size", "sort"
    );
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}$"
    );
    private static final Pattern SIGNED_INTEGER = Pattern.compile(
            "^-?[0-9]+$"
    );

    public FraudCaseAuditLogQuery validate(
            FraudCaseAuditLogQuery.Request request
    ) {
        if (request == null) {
            throw format("$", "REQUEST_REQUIRED", "Request is required");
        }
        Map<String, List<String>> parameters = request.queryParameters();
        if (!ALLOWED_QUERY_PARAMETERS.containsAll(parameters.keySet())) {
            throw format(
                    "$",
                    UNSUPPORTED_QUERY_PARAMETER,
                    "Query parameter is not supported"
            );
        }
        validateScalarCounts(parameters);

        UUID caseId = parseCaseId(request.caseId());
        int page = parseInteger(
                "page", single(parameters, "page"), 0, INVALID_PAGE_FORMAT
        );
        int size = parseInteger(
                "size", single(parameters, "size"), 20, INVALID_SIZE_FORMAT
        );
        if (page < 0) {
            throw domain(
                    "page", PAGE_OUT_OF_RANGE,
                    "page must be zero or greater"
            );
        }
        if (size < 1 || size > 100) {
            throw domain(
                    "size", SIZE_OUT_OF_RANGE,
                    "size must be between 1 and 100"
            );
        }
        return new FraudCaseAuditLogQuery(
                caseId,
                page,
                size,
                parseSort(single(parameters, "sort"))
        );
    }

    private void validateScalarCounts(
            Map<String, List<String>> parameters
    ) {
        for (String parameter : ALLOWED_QUERY_PARAMETERS) {
            List<String> values = parameters.get(parameter);
            if (values != null && values.size() != 1) {
                throw format(
                        parameter,
                        MULTIPLE_VALUES_NOT_ALLOWED,
                        parameter + " must be provided exactly once"
                );
            }
        }
    }

    private String single(
            Map<String, List<String>> parameters,
            String name
    ) {
        List<String> values = parameters.get(name);
        return values == null ? null : values.get(0);
    }

    private UUID parseCaseId(String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw format(
                    "caseId", INVALID_UUID_FORMAT,
                    "caseId must use the canonical UUID string format"
            );
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw format("caseId", INVALID_UUID_FORMAT, "caseId is invalid");
        }
        if (uuid.version() != 4) {
            throw format(
                    "caseId", INVALID_UUID_VERSION,
                    "caseId must be a UUID version 4"
            );
        }
        if (uuid.variant() != 2) {
            throw format(
                    "caseId", INVALID_UUID_VARIANT,
                    "caseId must use the RFC 4122 variant"
            );
        }
        return uuid;
    }

    private int parseInteger(
            String field,
            String value,
            int defaultValue,
            String code
    ) {
        if (value == null) {
            return defaultValue;
        }
        if (!SIGNED_INTEGER.matcher(value).matches()) {
            throw format(field, code, field + " must be an integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw format(field, code, field + " must be an integer");
        }
    }

    private Sort.Direction parseSort(String value) {
        String sort = value == null ? "changedAt,desc" : value;
        String[] parts = sort.split(",", -1);
        if (parts.length != 2) {
            throw format(
                    "sort", INVALID_SORT_FORMAT,
                    "sort must use field,direction format"
            );
        }
        if (!"changedAt".equals(parts[0])) {
            throw format(
                    "sort", UNSUPPORTED_SORT_FIELD,
                    "sort field is not supported"
            );
        }
        return switch (parts[1]) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw format(
                    "sort", UNSUPPORTED_SORT_DIRECTION,
                    "sort direction is not supported"
            );
        };
    }

    private FraudCaseValidationException format(
            String field,
            String code,
            String reason
    ) {
        return new FraudCaseValidationException(
                FraudCaseValidationType.FORMAT, field, code, reason
        );
    }

    private FraudCaseValidationException domain(
            String field,
            String code,
            String reason
    ) {
        return new FraudCaseValidationException(
                FraudCaseValidationType.DOMAIN, field, code, reason
        );
    }
}
