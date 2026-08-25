package com.aifds.backend.rule.client.dto;

import com.aifds.backend.externalrisk.domain.ExternalRiskContracts;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExternalRiskSnapshotRequest(
        @JsonProperty(required = true) String providerCode,
        @JsonProperty(required = true) ExternalRiskLookupStatus lookupStatus,
        @JsonProperty(required = true) ExternalRiskPolicyResult policyResult,
        @JsonProperty(required = true) Instant providerAsOf,
        @JsonProperty(required = true) Instant lookedUpAt,
        @JsonProperty(required = true) List<ExternalRiskMatchRequest> matches
) {

    public ExternalRiskSnapshotRequest {
        if (!ExternalRiskContracts.isProviderCode(providerCode)) {
            throw new IllegalArgumentException("providerCode is invalid");
        }
        Objects.requireNonNull(lookupStatus, "lookupStatus must not be null");
        Objects.requireNonNull(policyResult, "policyResult must not be null");
        Objects.requireNonNull(providerAsOf, "providerAsOf must not be null");
        Objects.requireNonNull(lookedUpAt, "lookedUpAt must not be null");
        RuleAnalysisDtoContracts.requireMicrosecondInstant(
                providerAsOf,
                "providerAsOf"
        );
        RuleAnalysisDtoContracts.requireMicrosecondInstant(
                lookedUpAt,
                "lookedUpAt"
        );
        Objects.requireNonNull(matches, "matches must not be null");
        matches.forEach(match -> Objects.requireNonNull(
                match,
                "matches must not contain null"
        ));
        if (matches.size() > 3) {
            throw new IllegalArgumentException(
                    "matches must contain at most 3 items"
            );
        }
        matches = List.copyOf(matches);
        ExternalRiskPolicyResult expected = matches.isEmpty()
                ? ExternalRiskPolicyResult.UNMATCHED
                : ExternalRiskPolicyResult.MATCHED;
        if (policyResult != expected) {
            throw new IllegalArgumentException(
                    "policyResult is inconsistent with matches"
            );
        }
    }
}
