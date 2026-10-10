package com.aifds.backend.detection.dto;

import java.util.UUID;

public record AdoptedDetectionResultResponse(
        UUID transactionId,
        String availability,
        Integer latestDetectionResultVersion,
        String latestAnalysisStatus,
        AdoptedDetectionResultItemResponse adoptedResult,
        String latestFailureCode
) {
    public AdoptedDetectionResultResponse(UUID transactionId, String availability,
            Integer latestDetectionResultVersion, String latestAnalysisStatus,
            AdoptedDetectionResultItemResponse adoptedResult) {
        this(transactionId, availability, latestDetectionResultVersion,
                latestAnalysisStatus, adoptedResult, null);
    }
}
