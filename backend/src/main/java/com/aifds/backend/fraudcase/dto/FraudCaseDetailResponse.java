package com.aifds.backend.fraudcase.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FraudCaseDetailResponse(
        @JsonProperty("case")
        FraudCaseDetailItemResponse fraudCase,
        String traceId
) {
}
