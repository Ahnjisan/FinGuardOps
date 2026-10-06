package com.aifds.backend.aireport.dto;

import com.aifds.backend.aireport.exception.AiReportException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AiReportUsageQuery(Instant from, Instant to, String provider, String model,
                                 String reportStatus, String reportSource, Boolean cacheHit,
                                 Boolean fallbackUsed, int page, int size, String sort) {
    private static final Set<String> FILTERS = Set.of("from", "to", "provider", "model",
            "reportStatus", "reportSource", "cacheHit", "fallbackUsed");
    private static final Set<String> SORTS = Set.of("requestedAt,asc", "requestedAt,desc",
            "aiRequestId,asc", "aiRequestId,desc");
    private static final Set<String> STATUSES = Set.of("PENDING", "GENERATING", "COMPLETED",
            "FALLBACK_COMPLETED", "FAILED");

    public static AiReportUsageQuery parse(Map<String, String[]> raw, boolean summary) {
        Set<String> allowed = summary ? FILTERS : Set.of("from", "to", "provider", "model",
                "reportStatus", "reportSource", "cacheHit", "fallbackUsed", "page", "size", "sort");
        if (!allowed.containsAll(raw.keySet()) || raw.values().stream().anyMatch(v -> v.length != 1)) {
            throw invalid(HttpStatus.BAD_REQUEST);
        }
        Instant from = time(one(raw, "from"));
        Instant to = time(one(raw, "to"));
        if (from == null || to == null) throw invalid(HttpStatus.BAD_REQUEST);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "to", "INVALID_TIME_RANGE");
        }
        String status = one(raw, "reportStatus");
        String source = one(raw, "reportSource");
        String provider = one(raw, "provider");
        String model = one(raw, "model");
        if ((status != null && !STATUSES.contains(status)) ||
                (source != null && !Set.of("LLM", "TEMPLATE_FALLBACK").contains(source)) ||
                (provider != null && !provider.matches("[A-Z][A-Z0-9_]{0,31}")) ||
                (model != null && !model.matches("[a-zA-Z0-9:_.-]{1,128}"))) {
            throw invalid(HttpStatus.BAD_REQUEST);
        }
        Boolean cache = bool(one(raw, "cacheHit"));
        Boolean fallback = bool(one(raw, "fallbackUsed"));
        int page = integer(one(raw, "page"), 0);
        int size = integer(one(raw, "size"), 20);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new AiReportException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    size < 1 || size > 100 ? "size" : "page", "OUT_OF_RANGE");
        }
        String sort = one(raw, "sort");
        if (sort == null) sort = "requestedAt,desc";
        if (!SORTS.contains(sort)) throw invalid(HttpStatus.BAD_REQUEST);
        return new AiReportUsageQuery(from, to, provider, model, status, source, cache,
                fallback, page, size, sort);
    }

    private static String one(Map<String, String[]> raw, String key) {
        String[] values = raw.get(key);
        return values == null ? null : values[0];
    }

    private static Instant time(String value) {
        if (value == null || !value.endsWith("Z")) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) { return null; }
    }

    private static Boolean bool(String value) {
        if (value == null) return null;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw invalid(HttpStatus.BAD_REQUEST);
    }

    private static int integer(String value, int defaultValue) {
        if (value == null) return defaultValue;
        if (!value.matches("-?[0-9]{1,10}")) throw invalid(HttpStatus.BAD_REQUEST);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw invalid(HttpStatus.BAD_REQUEST); }
    }

    private static AiReportException invalid(HttpStatus status) {
        return new AiReportException(status, "VALIDATION_ERROR");
    }
}
