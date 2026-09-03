package com.aifds.backend.security.principal;

public final class FinGuardOpsAuthority {

    public static final String TRANSACTION_READ = "transaction:read";
    public static final String BEHAVIOR_EVENT_READ = "behavior-event:read";
    public static final String DETECTION_READ = "detection:read";
    public static final String CASE_READ = "case:read";
    public static final String CASE_NOTE_READ = "case-note:read";
    public static final String CASE_AUDIT_READ = "case-audit:read";
    public static final String AI_REPORT_READ = "ai-report:read";
    public static final String CASE_WORKFLOW_WRITE = "case:workflow:write";
    public static final String CASE_NOTE_WRITE = "case-note:write";
    public static final String AI_REPORT_CREATE = "ai-report:create";
    public static final String CASE_RESOLUTION_WRITE =
            "case:resolution:write";
    public static final String RULE_VERSION_READ = "rule-version:read";
    public static final String RULE_VERSION_PUBLISH = "rule-version:publish";
    public static final String RECOVERY_INSPECT = "recovery:inspect";
    public static final String RECOVERY_EXECUTE = "recovery:execute";
    public static final String PLATFORM_READ = "platform:read";
    public static final String AI_OPERATIONS_READ = "ai-operations:read";
    public static final String AI_USAGE_READ = "ai-usage:read";
    public static final String TRANSACTION_INTAKE = "transaction:intake";
    public static final String BEHAVIOR_EVENT_INTAKE =
            "behavior-event:intake";

    private FinGuardOpsAuthority() {
    }
}
