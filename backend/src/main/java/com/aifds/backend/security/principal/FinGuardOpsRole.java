package com.aifds.backend.security.principal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public enum FinGuardOpsRole {

    FDS_VIEWER(
            FinGuardOpsPrincipal.Type.USER,
            FinGuardOpsAuthority.TRANSACTION_READ,
            FinGuardOpsAuthority.BEHAVIOR_EVENT_READ,
            FinGuardOpsAuthority.DETECTION_READ,
            FinGuardOpsAuthority.CASE_READ,
            FinGuardOpsAuthority.CASE_NOTE_READ,
            FinGuardOpsAuthority.CASE_AUDIT_READ,
            FinGuardOpsAuthority.AI_REPORT_READ
    ),
    FDS_ANALYST(
            FinGuardOpsPrincipal.Type.USER,
            FinGuardOpsAuthority.TRANSACTION_READ,
            FinGuardOpsAuthority.BEHAVIOR_EVENT_READ,
            FinGuardOpsAuthority.DETECTION_READ,
            FinGuardOpsAuthority.CASE_READ,
            FinGuardOpsAuthority.CASE_NOTE_READ,
            FinGuardOpsAuthority.CASE_AUDIT_READ,
            FinGuardOpsAuthority.AI_REPORT_READ,
            FinGuardOpsAuthority.CASE_WORKFLOW_WRITE,
            FinGuardOpsAuthority.CASE_NOTE_WRITE,
            FinGuardOpsAuthority.AI_REPORT_CREATE
    ),
    FDS_APPROVER(
            FinGuardOpsPrincipal.Type.USER,
            FinGuardOpsAuthority.TRANSACTION_READ,
            FinGuardOpsAuthority.BEHAVIOR_EVENT_READ,
            FinGuardOpsAuthority.DETECTION_READ,
            FinGuardOpsAuthority.CASE_READ,
            FinGuardOpsAuthority.CASE_NOTE_READ,
            FinGuardOpsAuthority.CASE_AUDIT_READ,
            FinGuardOpsAuthority.AI_REPORT_READ,
            FinGuardOpsAuthority.CASE_RESOLUTION_WRITE
    ),
    RULE_OPERATOR(
            FinGuardOpsPrincipal.Type.USER,
            FinGuardOpsAuthority.RULE_VERSION_READ,
            FinGuardOpsAuthority.RULE_VERSION_PUBLISH
    ),
    RECOVERY_OPERATOR(
            FinGuardOpsPrincipal.Type.USER,
            FinGuardOpsAuthority.RECOVERY_INSPECT,
            FinGuardOpsAuthority.RECOVERY_EXECUTE
    ),
    PLATFORM_ADMIN(
            FinGuardOpsPrincipal.Type.USER,
            FinGuardOpsAuthority.PLATFORM_READ,
            FinGuardOpsAuthority.AI_OPERATIONS_READ,
            FinGuardOpsAuthority.AI_USAGE_READ
    ),
    TRANSACTION_INGESTOR(
            FinGuardOpsPrincipal.Type.SERVICE,
            FinGuardOpsAuthority.TRANSACTION_INTAKE
    ),
    BEHAVIOR_INGESTOR(
            FinGuardOpsPrincipal.Type.SERVICE,
            FinGuardOpsAuthority.BEHAVIOR_EVENT_INTAKE
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
