package com.aifds.backend.fraudcase.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = InvestigationNoteCreateRequestDeserializer.class)
public record InvestigationNoteCreateRequest(String content, Long expectedVersion) {
}
