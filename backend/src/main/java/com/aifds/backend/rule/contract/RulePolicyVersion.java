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
        if (snapshots.size() == 15 || snapshots.stream().anyMatch(snapshot ->
                RuleV1ContractRegistry.VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND
                        .equals(snapshot.ruleCode()))) {
            if (snapshots.size() != 15 || from(snapshots.subList(0, 14)) != 6
                    || !RuleV1ContractRegistry.VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND
                            .equals(snapshots.get(14).ruleCode())
                    || snapshots.get(14).versionNumber() != 1) {
                throw new IllegalArgumentException("Invalid SCN-008 RuleVersion set");
            }
            return 7;
        }
        if (snapshots.size() == 14 || snapshots.stream().anyMatch(snapshot ->
                RuleV1ContractRegistry.VERIFIED_INBOUND_ATM_DRAIN.equals(snapshot.ruleCode())
                || RuleV1ContractRegistry.VERIFIED_LOAN_NEW_RECIPIENT_DRAIN
                        .equals(snapshot.ruleCode()))) {
            if (snapshots.size() != 14 || from(snapshots.subList(0, 12)) != 5
                    || !RuleV1ContractRegistry.VERIFIED_INBOUND_ATM_DRAIN.equals(
                            snapshots.get(12).ruleCode())
                    || !RuleV1ContractRegistry.VERIFIED_LOAN_NEW_RECIPIENT_DRAIN.equals(
                            snapshots.get(13).ruleCode())
                    || snapshots.get(12).versionNumber() != 1
                    || snapshots.get(13).versionNumber() != 1) {
                throw new IllegalArgumentException("Invalid SCN-006/007 RuleVersion set");
            }
            return 6;
        }
        if (snapshots.size() == 12 || snapshots.stream().anyMatch(snapshot ->
                RuleV1ContractRegistry.DISTRIBUTED_OUTBOUND_WINDOW.equals(snapshot.ruleCode())
                || RuleV1ContractRegistry.MANY_CUSTOMER_INBOUND_WINDOW.equals(snapshot.ruleCode()))) {
            if (snapshots.size() != 12 || from(snapshots.subList(0, 10)) != 4
                    || !RuleV1ContractRegistry.DISTRIBUTED_OUTBOUND_WINDOW.equals(
                            snapshots.get(10).ruleCode())
                    || !RuleV1ContractRegistry.MANY_CUSTOMER_INBOUND_WINDOW.equals(
                            snapshots.get(11).ruleCode())
                    || snapshots.get(10).versionNumber() != 1
                    || snapshots.get(11).versionNumber() != 1) {
                throw new IllegalArgumentException("Invalid SCN-004/005 RuleVersion set");
            }
            return 5;
        }
        if (snapshots.size() == 10 || snapshots.stream().anyMatch(snapshot ->
                RuleV1ContractRegistry.CUSTOMER_BASELINE_HIGH_AMOUNT.equals(snapshot.ruleCode())
                || RuleV1ContractRegistry.EXTERNAL_RISK_DEVICE_MATCH.equals(snapshot.ruleCode())
                || RuleV1ContractRegistry.MOCK_APP_SECURITY_ALERT.equals(snapshot.ruleCode())
                || RuleV1ContractRegistry.MATERIAL_LIMIT_UPLIFT.equals(snapshot.ruleCode())
                || RuleV1ContractRegistry.MOCK_OVERSEAS_NETWORK.equals(snapshot.ruleCode()))) {
            Map<String, Integer> expected = Map.of(
                    RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT, 3,
                    RuleV1ContractRegistry.RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT, 4,
                    RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT, 4,
                    RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER, 4,
                    RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT, 1,
                    RuleV1ContractRegistry.CUSTOMER_BASELINE_HIGH_AMOUNT, 1,
                    RuleV1ContractRegistry.EXTERNAL_RISK_DEVICE_MATCH, 1,
                    RuleV1ContractRegistry.MOCK_APP_SECURITY_ALERT, 1,
                    RuleV1ContractRegistry.MATERIAL_LIMIT_UPLIFT, 1,
                    RuleV1ContractRegistry.MOCK_OVERSEAS_NETWORK, 1
            );
            Map<String, Integer> actual;
            try {
                actual = snapshots.stream().collect(Collectors.toMap(
                        RuleVersionSnapshotRequest::ruleCode,
                        RuleVersionSnapshotRequest::versionNumber));
            } catch (IllegalStateException duplicate) {
                throw new IllegalArgumentException("Duplicate Rule in SCN-001/002 set", duplicate);
            }
            if (snapshots.size() != 10 || !actual.equals(expected)) {
                throw new IllegalArgumentException("Invalid SCN-001/002 RuleVersion set");
            }
            return 4;
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
