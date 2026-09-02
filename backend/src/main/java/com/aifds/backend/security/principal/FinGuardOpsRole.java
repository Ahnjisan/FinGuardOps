package com.aifds.backend.security.principal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public enum FinGuardOpsRole {

    FDS_VIEWER(
            FinGuardOpsPrincipal.Type.USER,
            "transaction:read",
            "behavior-event:read",
            "detection:read",
            "case:read",
            "case-note:read",
            "case-audit:read",
            "ai-report:read"
    ),
    FDS_ANALYST(
            FinGuardOpsPrincipal.Type.USER,
            "transaction:read",
            "behavior-event:read",
            "detection:read",
            "case:read",
            "case-note:read",
            "case-audit:read",
            "ai-report:read",
            "case:workflow:write",
            "case-note:write",
            "ai-report:create"
    ),
    FDS_APPROVER(
            FinGuardOpsPrincipal.Type.USER,
            "transaction:read",
            "behavior-event:read",
            "detection:read",
            "case:read",
            "case-note:read",
            "case-audit:read",
            "ai-report:read",
            "case:resolution:write"
    ),
    RULE_OPERATOR(
            FinGuardOpsPrincipal.Type.USER,
            "rule-version:read",
            "rule-version:publish"
    ),
    RECOVERY_OPERATOR(
            FinGuardOpsPrincipal.Type.USER,
            "recovery:inspect",
            "recovery:execute"
    ),
    PLATFORM_ADMIN(
            FinGuardOpsPrincipal.Type.USER,
            "platform:read",
            "ai-operations:read",
            "ai-usage:read"
    ),
    TRANSACTION_INGESTOR(
            FinGuardOpsPrincipal.Type.SERVICE,
            "transaction:intake"
    ),
    BEHAVIOR_INGESTOR(
            FinGuardOpsPrincipal.Type.SERVICE,
            "behavior-event:intake"
    );

    private final FinGuardOpsPrincipal.Type principalType;
    private final Set<String> authorities;

    FinGuardOpsRole(
            FinGuardOpsPrincipal.Type principalType,
            String... authorities
    ) {
        this.principalType = principalType;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Collections.addAll(values, authorities);
        this.authorities = Collections.unmodifiableSet(values);
    }

    public FinGuardOpsPrincipal.Type principalType() {
        return principalType;
    }

    public Set<String> authorities() {
        return authorities;
    }
}
