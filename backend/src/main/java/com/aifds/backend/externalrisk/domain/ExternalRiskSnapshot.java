package com.aifds.backend.externalrisk.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExternalRiskSnapshot(
        UUID transactionId,
        Instant evaluationCutoffAt,
        Instant lookedUpAt,
        String providerCode,
        Instant providerAsOf,
        ExternalRiskLookupStatus lookupStatus,
        ExternalRiskPolicyResult policyResult,
        List<ExternalRiskMatch> matches
) {

    public ExternalRiskSnapshot {
        if (!ExternalRiskContracts.isUuidV4(transactionId)
                || !ExternalRiskContracts.isMicrosecondInstant(evaluationCutoffAt)
                || !ExternalRiskContracts.isMicrosecondInstant(lookedUpAt)
                || !ExternalRiskContracts.isProviderCode(providerCode)
                || !ExternalRiskContracts.isMicrosecondInstant(providerAsOf)
                || providerAsOf.isAfter(lookedUpAt)
                || lookupStatus != ExternalRiskLookupStatus.SUCCEEDED
                || policyResult == null
                || !ExternalRiskContracts.hasValidUniqueMatches(matches)) {
            throw new IllegalArgumentException("External Risk snapshot is invalid");
        }
        matches = List.copyOf(matches);
        ExternalRiskPolicyResult expected = matches.isEmpty()
                ? ExternalRiskPolicyResult.UNMATCHED
                : ExternalRiskPolicyResult.MATCHED;
        if (policyResult != expected) {
            throw new IllegalArgumentException(
                    "External Risk snapshot policy result is inconsistent"
            );
        }
    }
}
