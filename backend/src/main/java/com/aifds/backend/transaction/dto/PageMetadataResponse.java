package com.aifds.backend.transaction.dto;

public record PageMetadataResponse(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
