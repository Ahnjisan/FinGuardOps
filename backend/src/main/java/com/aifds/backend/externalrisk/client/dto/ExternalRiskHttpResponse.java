package com.aifds.backend.externalrisk.client.dto;

import java.time.Instant;
import java.util.List;

public record ExternalRiskHttpResponse(
        String providerCode,
        Instant providerAsOf,
        List<ExternalRiskHttpMatchResponse> matches
) {
}
