package com.aifds.backend.fraudcase.dto;

public record FraudCasePageMetadataResponse(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
