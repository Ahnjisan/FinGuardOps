package com.aifds.backend.fraudcase.query;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FraudCaseTransactionQuery(UUID caseId, int page, int size) {
    public record Request(String caseId, Map<String, List<String>> queryParameters) {
        public Request {
            queryParameters = queryParameters == null ? Map.of()
                    : queryParameters.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())
                    ));
        }
    }
}
