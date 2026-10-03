package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.query.FraudCaseTransactionQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FraudCaseTransactionQueryValidator {
    private static final Set<String> ALLOWED = Set.of("page", "size");
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );
    private static final Pattern INTEGER = Pattern.compile("^-?[0-9]+$");

    public FraudCaseTransactionQuery validate(FraudCaseTransactionQuery.Request request) {
        if (request == null) {
            throw format("$", "REQUEST_REQUIRED", "Request is required");
        }
        Map<String, List<String>> parameters = request.queryParameters();
        if (!ALLOWED.containsAll(parameters.keySet())) {
            throw format("$", "UNSUPPORTED_QUERY_PARAMETER", "Query parameter is not supported");
        }
        for (String name : ALLOWED) {
            List<String> values = parameters.get(name);
            if (values != null && values.size() != 1) {
                throw format(name, "MULTIPLE_VALUES_NOT_ALLOWED", name + " must be provided exactly once");
            }
        }
        UUID caseId = caseId(request.caseId());
        int page = integer("page", parameters, 0, "INVALID_PAGE_FORMAT");
        int size = integer("size", parameters, 20, "INVALID_SIZE_FORMAT");
        if (page < 0) {
            throw domain("page", "PAGE_OUT_OF_RANGE", "page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw domain("size", "SIZE_OUT_OF_RANGE", "size must be between 1 and 100");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw domain("page", "PAGE_OUT_OF_RANGE", "page is too large for the requested size");
        }
        return new FraudCaseTransactionQuery(caseId, page, size);
    }

    private UUID caseId(String raw) {
        if (raw == null || !UUID_FORMAT.matcher(raw).matches()) {
            throw format("caseId", "INVALID_UUID_FORMAT", "caseId must use the canonical UUID string format");
        }
        UUID value;
        try {
            value = UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw format("caseId", "INVALID_UUID_FORMAT", "caseId is invalid");
        }
        if (value.version() != 4) {
            throw format("caseId", "INVALID_UUID_VERSION", "caseId must be a UUID version 4");
        }
        if (value.variant() != 2) {
            throw format("caseId", "INVALID_UUID_VARIANT", "caseId must use the RFC 4122 variant");
        }
        return value;
    }

    private int integer(String name, Map<String, List<String>> parameters, int fallback, String code) {
        List<String> values = parameters.get(name);
        if (values == null) {
            return fallback;
        }
        String raw = values.get(0);
        if (!INTEGER.matcher(raw).matches()) {
            throw format(name, code, name + " must be an integer");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw format(name, code, name + " must be an integer");
        }
    }

    private FraudCaseValidationException format(String field, String code, String reason) {
        return new FraudCaseValidationException(FraudCaseValidationType.FORMAT, field, code, reason);
    }

    private FraudCaseValidationException domain(String field, String code, String reason) {
        return new FraudCaseValidationException(FraudCaseValidationType.DOMAIN, field, code, reason);
    }
}
