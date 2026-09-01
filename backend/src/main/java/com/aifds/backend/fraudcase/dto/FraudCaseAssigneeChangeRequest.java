package com.aifds.backend.fraudcase.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = FraudCaseAssigneeChangeRequestDeserializer.class)
public record FraudCaseAssigneeChangeRequest(
        String assigneeRef,
        @JsonIgnore boolean assigneeRefPresent,
        String reasonCode,
        Long expectedVersion
) {
}
