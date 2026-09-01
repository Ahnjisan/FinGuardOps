package com.aifds.backend.fraudcase.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = FraudCaseStatusChangeRequestDeserializer.class)
public record FraudCaseStatusChangeRequest(
        String targetStatus,
        String assigneeRef,
        @JsonIgnore boolean assigneeRefPresent,
        String reasonCode,
        Long expectedVersion
) {
}
