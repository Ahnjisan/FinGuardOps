package com.aifds.backend.rule.contract;

import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The persisted RuleVersion set, never the HTTP wire version, selects scoring. */
public final class RulePolicyVersion {
    private RulePolicyVersion() { }

    public static int from(List<RuleVersionSnapshotRequest> snapshots) {
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("RuleVersion set is empty");
        }
        if (snapshots.size() == 5 || snapshots.stream().anyMatch(snapshot ->
                RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT.equals(
                        snapshot.ruleCode()))) {
            Map<String, Integer> expected = Map.of(
                    RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT, 3,
                    RuleV1ContractRegistry.RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT, 3,
                    RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT, 3,
                    RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER, 3,
                    RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT, 1
            );
            Map<String, Integer> actual;
            try {
                actual = snapshots.stream().collect(Collectors.toMap(
                        RuleVersionSnapshotRequest::ruleCode,
                        RuleVersionSnapshotRequest::versionNumber));
            } catch (IllegalStateException duplicate) {
                throw new IllegalArgumentException("Duplicate Rule in SCN-003 set", duplicate);
            }
            if (snapshots.size() != 5 || !actual.equals(expected)) {
                throw new IllegalArgumentException("Invalid SCN-003 RuleVersion set");
            }
            return 3;
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
