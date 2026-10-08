package com.aifds.backend.rule.contract;

import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;

import java.util.List;

/** The persisted RuleVersion set, never the HTTP wire version, selects scoring. */
public final class RulePolicyVersion {
    private RulePolicyVersion() { }

    public static int from(List<RuleVersionSnapshotRequest> snapshots) {
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("RuleVersion set is empty");
        }
        int version = snapshots.get(0).versionNumber();
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("Unsupported Rule policy version");
        }
        if (version == 2 && snapshots.size() != 4) {
            throw new IllegalArgumentException("Rule v2 requires four RuleVersions");
        }
        for (RuleVersionSnapshotRequest snapshot : snapshots) {
            if (snapshot.versionNumber() != version) {
                throw new IllegalArgumentException("Mixed RuleVersion sets");
            }
        }
        return version;
    }

    public static String scoringPolicy(int version) {
        return "scoring-policy-v" + version;
    }

    public static String feature(int version) {
        return "rule-v" + version;
    }
}
