package com.aifds.backend.fraudcase.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = FraudCaseResolutionRequestDeserializer.class)
public record FraudCaseResolutionRequest(
        String finalDisposition,
        String reasonCode,
        Long expectedVersion
) {
}
