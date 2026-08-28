package com.aifds.recovery.idempotency;

import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record IdempotencyRecoveryCommandArguments(
        Action action,
        Duration threshold,
        int pageSize,
        Long recordId
) {

    public static final String PREFIX =
            "--finguardops.idempotency-recovery.";

    private static final String ENABLED = "enabled";
    private static final String ACTION = "action";
    private static final String THRESHOLD = "threshold";
    private static final String PAGE_SIZE = "page-size";
    private static final String RECORD_ID = "record-id";
    private static final Set<String> ALLOWED_OPTIONS = Set.of(
            ENABLED,
            ACTION,
            THRESHOLD,
            PAGE_SIZE,
            RECORD_ID
    );
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");
    private static final Pattern CANONICAL_POSITIVE_LONG = Pattern.compile(
            "[1-9][0-9]*"
    );

    public enum Action {
        INSPECT,
        RECOVER
    }

    public static boolean hasRecoveryPrefix(String[] args) {
        if (args == null) {
            return false;
        }
        for (String argument : args) {
            if (argument != null && argument.startsWith(PREFIX)) {
                return true;
            }
        }
        return false;
    }

    public static IdempotencyRecoveryCommandArguments parse(String[] args) {
        Map<String, String> options = parseOptions(args);
        if (!"true".equals(options.get(ENABLED))) {
            throw invalid();
        }

        String rawAction = required(options, ACTION);
        return switch (rawAction) {
            case "inspect" -> inspect(options);
            case "recover" -> recover(options);
            default -> throw invalid();
        };
    }

    private static Map<String, String> parseOptions(String[] args) {
        if (args == null || args.length == 0) {
            throw invalid();
        }
        Map<String, String> options = new HashMap<>();
        for (String argument : args) {
            if (argument == null || !argument.startsWith(PREFIX)) {
                throw invalid();
            }
            int equalsIndex = argument.indexOf('=', PREFIX.length());
            if (equalsIndex < 0
                    || equalsIndex == PREFIX.length()
                    || equalsIndex == argument.length() - 1
                    || argument.indexOf('=', equalsIndex + 1) >= 0) {
                throw invalid();
            }
            String name = argument.substring(PREFIX.length(), equalsIndex);
            String value = argument.substring(equalsIndex + 1);
            if (!ALLOWED_OPTIONS.contains(name)
                    || value.isEmpty()
                    || !value.equals(value.trim())
                    || options.putIfAbsent(name, value) != null) {
                throw invalid();
            }
        }
        return options;
    }

    private static IdempotencyRecoveryCommandArguments inspect(
            Map<String, String> options
    ) {
        if (options.containsKey(RECORD_ID)) {
            throw invalid();
        }
        Duration threshold = options.containsKey(THRESHOLD)
                ? parseThreshold(options.get(THRESHOLD))
                : IdempotencyRecoveryService.DEFAULT_THRESHOLD;
        int pageSize = options.containsKey(PAGE_SIZE)
                ? parsePageSize(options.get(PAGE_SIZE))
                : IdempotencyRecoveryService.DEFAULT_PAGE_SIZE;
        return new IdempotencyRecoveryCommandArguments(
                Action.INSPECT,
                threshold,
                pageSize,
                null
        );
    }

    private static IdempotencyRecoveryCommandArguments recover(
            Map<String, String> options
    ) {
        if (options.containsKey(THRESHOLD)
                || options.containsKey(PAGE_SIZE)) {
            throw invalid();
        }
        String rawRecordId = required(options, RECORD_ID);
        if (!CANONICAL_POSITIVE_LONG.matcher(rawRecordId).matches()) {
            throw invalid();
        }
        try {
            return new IdempotencyRecoveryCommandArguments(
                    Action.RECOVER,
                    null,
                    0,
                    Long.parseLong(rawRecordId)
            );
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static Duration parseThreshold(String rawThreshold) {
        final Duration threshold;
        try {
            threshold = Duration.parse(rawThreshold);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
        if (threshold.compareTo(IdempotencyRecoveryService.MIN_THRESHOLD) < 0
                || threshold.compareTo(
                IdempotencyRecoveryService.MAX_THRESHOLD
        ) > 0) {
            throw invalid();
        }
        return threshold;
    }

    private static int parsePageSize(String rawPageSize) {
        if (!DECIMAL.matcher(rawPageSize).matches()) {
            throw invalid();
        }
        final int pageSize;
        try {
            pageSize = Integer.parseInt(rawPageSize);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        if (pageSize < IdempotencyRecoveryService.MIN_PAGE_SIZE
                || pageSize > IdempotencyRecoveryService.MAX_PAGE_SIZE) {
            throw invalid();
        }
        return pageSize;
    }

    private static String required(
            Map<String, String> options,
            String name
    ) {
        String value = options.get(name);
        if (value == null || value.isEmpty()) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid recovery command");
    }
}
