package com.aifds.backend.externalrisk.domain;

import java.time.Instant;
import java.util.List;

public record ExternalRiskProviderResponse(
        String providerCode,
        Instant providerAsOf,
        List<ExternalRiskMatch> matches
) {

    public ExternalRiskProviderResponse {
        matches = ExternalRiskContracts.copyProviderResponseMatches(matches);
    }
}
