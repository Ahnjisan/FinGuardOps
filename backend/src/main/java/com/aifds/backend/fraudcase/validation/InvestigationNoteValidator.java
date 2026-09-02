package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.command.FraudCaseNoteCommand;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class InvestigationNoteValidator {

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Set<String> LIST_FIELDS = Set.of("page", "size", "sort");

    public FraudCaseNoteCommand.Create validateCreate(
            String rawCaseId,
            InvestigationNoteCreateRequest request
    ) {
        UUID caseId = parseUuid(rawCaseId);
        if (request == null) {
            throw format("$", "REQUEST_REQUIRED", "Note request is required");
        }
        if (request.content() == null) {
            throw format("content", "REQUIRED_FIELD", "content is required");
        }
        if (request.expectedVersion() == null) {
            throw format("expectedVersion", "REQUIRED_FIELD", "expectedVersion is required");
        }
        if (request.expectedVersion() < 0) {
            throw domain("expectedVersion", "INVALID_EXPECTED_VERSION", "expectedVersion must be zero or greater");
        }
        return new FraudCaseNoteCommand.Create(caseId, request.content(), request.expectedVersion());
    }

    public FraudCaseNoteCommand.ListQuery validateList(
            String rawCaseId,
            Map<String, List<String>> params
    ) {
        UUID caseId = parseUuid(rawCaseId);
        for (String key : params.keySet()) {
            if (!LIST_FIELDS.contains(key)) {
                throw format(key, "UNKNOWN_QUERY_PARAMETER", "Unknown note list query parameter");
            }
            if (params.get(key).size() != 1) {
                throw format(key, "DUPLICATE_QUERY_PARAMETER", "Note list query parameter must occur once");
            }
        }
        int page = parseInteger(params, "page", 0);
        int size = parseInteger(params, "size", 20);
        String sort = single(params, "sort", "createdAt,asc");
        if (page < 0) {
            throw domain("page", "INVALID_PAGE", "page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw domain("size", "INVALID_SIZE", "size must be between 1 and 100");
        }
        FraudCaseNoteCommand.Direction direction = switch (sort) {
            case "createdAt,asc" -> FraudCaseNoteCommand.Direction.ASC;
            case "createdAt,desc" -> FraudCaseNoteCommand.Direction.DESC;
            default -> throw format("sort", "INVALID_SORT", "sort must be createdAt,asc or createdAt,desc");
        };
        return new FraudCaseNoteCommand.ListQuery(caseId, page, size, direction);
    }

    public void validateContent(String content) {
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints < 1 || content.codePoints().allMatch(this::isWhitespace)) {
            throw domain("content", "INVALID_CONTENT", "content must contain a non-whitespace code point");
        }
        if (codePoints > 4_000) {
            throw domain("content", "CONTENT_TOO_LONG", "content must not exceed 4000 Unicode code points");
        }
        if (content.codePoints().anyMatch(this::isForbiddenControl)) {
            throw domain("content", "INVALID_CONTROL_CHARACTER", "content contains a forbidden control character");
        }
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private boolean isForbiddenControl(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL
                && codePoint != '\r' && codePoint != '\n';
    }

    private UUID parseUuid(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) {
            throw format("caseId", "INVALID_UUID_FORMAT", "caseId must be a canonical lowercase UUID v4");
        }
        return UUID.fromString(value);
    }

    private int parseInteger(Map<String, List<String>> params, String field, int defaultValue) {
        String value = single(params, field, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw format(field, "INVALID_INTEGER", field + " must be an integer");
        }
    }

    private String single(Map<String, List<String>> params, String field, String defaultValue) {
        List<String> values = params.get(field);
        return values == null ? defaultValue : values.get(0);
    }

    private InvestigationNoteValidationException format(String field, String code, String reason) {
        return new InvestigationNoteValidationException(InvestigationNoteValidationType.FORMAT, field, code, reason);
    }

    private InvestigationNoteValidationException domain(String field, String code, String reason) {
        return new InvestigationNoteValidationException(InvestigationNoteValidationType.DOMAIN, field, code, reason);
    }
}
