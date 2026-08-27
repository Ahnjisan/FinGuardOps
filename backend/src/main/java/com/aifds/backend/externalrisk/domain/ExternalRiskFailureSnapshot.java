package com.aifds.backend.externalrisk.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExternalRiskFailureSnapshot(
        ResponseBody responseBody,
        int httpStatus,
        ExternalRiskFailureCategory failureCategory,
        Instant finalizedAt
) {

    private static final String DEPENDENCY_MESSAGE =
            "탐지 서비스를 사용할 수 없습니다.";
    private static final String INTERNAL_ERROR_MESSAGE =
            "요청을 처리하는 중 오류가 발생했습니다.";

    public ExternalRiskFailureSnapshot {
        Objects.requireNonNull(responseBody, "responseBody must not be null");
        Objects.requireNonNull(
                failureCategory,
                "failureCategory must not be null"
        );
        Objects.requireNonNull(finalizedAt, "finalizedAt must not be null");

        Mapping expected = mappingFor(failureCategory);
        if (httpStatus != expected.httpStatus()
                || !responseBody.code().equals(expected.code())
                || !responseBody.message().equals(expected.message())
                || !responseBody.fieldErrors().isEmpty()) {
            throw new IllegalArgumentException(
                    "External Risk failure snapshot mapping is invalid"
            );
        }
    }

    public static ExternalRiskFailureSnapshot from(
            ExternalRiskFailureCategory category,
            Instant finalizedAt
    ) {
        Mapping mapping = mappingFor(category);
        return new ExternalRiskFailureSnapshot(
                new ResponseBody(
                        mapping.code(),
                        mapping.message(),
                        List.of()
                ),
                mapping.httpStatus(),
                category,
                finalizedAt
        );
    }

    public static String failureCodeFor(
            ExternalRiskFailureCategory category
    ) {
        return mappingFor(category).code();
    }

    private static Mapping mappingFor(ExternalRiskFailureCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return switch (category) {
            case TIMEOUT -> new Mapping(
                    503,
                    "DEPENDENCY_TIMEOUT",
                    DEPENDENCY_MESSAGE
            );
            case UNAVAILABLE -> new Mapping(
                    503,
                    "DEPENDENCY_UNAVAILABLE",
                    DEPENDENCY_MESSAGE
            );
            case INVALID_REQUEST,
                 UNSUPPORTED_CAPABILITY,
                 INVALID_RESPONSE,
                 TRANSFORMATION_ERROR -> new Mapping(
                    500,
                    "INTERNAL_ERROR",
                    INTERNAL_ERROR_MESSAGE
            );
        };
    }

    public record ResponseBody(
            String code,
            String message,
            List<String> fieldErrors
    ) {

        public ResponseBody {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            fieldErrors = List.copyOf(Objects.requireNonNull(
                    fieldErrors,
                    "fieldErrors must not be null"
            ));
        }
    }

    private record Mapping(int httpStatus, String code, String message) {
    }
}
