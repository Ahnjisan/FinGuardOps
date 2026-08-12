package com.aifds.backend.rule.client;

import java.util.Objects;
import java.util.OptionalInt;

public final class RuleAnalysisClientException extends RuntimeException {

    private final RuleAnalysisClientErrorCategory category;
    private final Integer httpStatus;

    RuleAnalysisClientException(
            RuleAnalysisClientErrorCategory category,
            Integer httpStatus
    ) {
        super(safeMessage(category));
        this.category = category;
        this.httpStatus = httpStatus;
    }

    private static String safeMessage(RuleAnalysisClientErrorCategory category) {
        return "AI Service Rule analysis call failed: " + Objects.requireNonNull(
                category,
                "category must not be null"
        );
    }

    public RuleAnalysisClientErrorCategory category() {
        return category;
    }

    public OptionalInt httpStatus() {
        return httpStatus == null
                ? OptionalInt.empty()
                : OptionalInt.of(httpStatus);
    }
}
