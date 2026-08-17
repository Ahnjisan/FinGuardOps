package com.aifds.backend.rule.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RuleV1DefaultRuleSetPublicationResult(
        PublicationOutcome outcome,
        List<UUID> ruleVersionIds,
        Instant effectiveFrom,
        Instant publishedAt,
        String ruleSetVersion
) {

    public RuleV1DefaultRuleSetPublicationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        ruleVersionIds = List.copyOf(
                Objects.requireNonNull(
                        ruleVersionIds,
                        "ruleVersionIds must not be null"
                )
        );
        if (ruleVersionIds.size() != 4) {
            throw new IllegalArgumentException(
                    "The default Rule v1 result requires four RuleVersion IDs"
            );
        }
        Objects.requireNonNull(
                effectiveFrom,
                "effectiveFrom must not be null"
        );
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (ruleSetVersion == null
                || !ruleSetVersion.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "ruleSetVersion must be a lowercase SHA-256 value"
            );
        }
    }

    public enum PublicationOutcome {
        PUBLISHED,
        ALREADY_PUBLISHED
    }
}
