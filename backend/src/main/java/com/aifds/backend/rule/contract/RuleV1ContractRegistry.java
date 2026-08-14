package com.aifds.backend.rule.contract;

import java.util.Map;
import java.util.Set;

public final class RuleV1ContractRegistry {

    private static final RuleAnalysisMetadata RULE_ANALYSIS_METADATA =
            new RuleAnalysisMetadata(
                    "scoring-policy-v1",
                    "rule-v1",
                    null
            );

    public static final String TRANSFER_ABSOLUTE_HIGH_AMOUNT =
            "TRANSFER_ABSOLUTE_HIGH_AMOUNT";
    public static final String RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT =
            "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT";
    public static final String RECENT_SECURITY_CHANGE_HIGH_AMOUNT =
            "RECENT_SECURITY_CHANGE_HIGH_AMOUNT";
    public static final String RECENT_BENEFICIARY_TRANSFER =
            "RECENT_BENEFICIARY_TRANSFER";

    private static final Map<String, Set<String>> ALLOWED_REASON_CODES =
            Map.of(
                    TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                    Set.of(TRANSFER_ABSOLUTE_HIGH_AMOUNT),
                    RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                    Set.of(RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT),
                    RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                    Set.of(RECENT_SECURITY_CHANGE_HIGH_AMOUNT),
                    RECENT_BENEFICIARY_TRANSFER,
                    Set.of(RECENT_BENEFICIARY_TRANSFER)
            );
    private static final Set<String> SUPPORTED_REASON_CODES = Set.of(
            TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            RECENT_BENEFICIARY_TRANSFER
    );
    private static final Map<String, String> DISPLAY_DESCRIPTIONS = Map.of(
            TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            "절대 고액 이체",
            RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            "최근 기기 등록 이벤트가 있는 고액 이체",
            RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            "최근 보안정보 변경 시퀀스가 있는 고액 이체",
            RECENT_BENEFICIARY_TRANSFER,
            "최근 등록 수취인 이체"
    );

    private RuleV1ContractRegistry() {
    }

    public static Set<String> allowedReasonCodesFor(String ruleCode) {
        Set<String> allowedReasonCodes = ALLOWED_REASON_CODES.get(ruleCode);
        if (allowedReasonCodes == null) {
            throw new IllegalArgumentException(
                    "Unsupported Rule v1 ruleCode: " + ruleCode
            );
        }
        return allowedReasonCodes;
    }

    public static void requireCompatible(
            String ruleCode,
            String reasonCode
    ) {
        Set<String> allowedReasonCodes = allowedReasonCodesFor(ruleCode);
        if (reasonCode == null
                || !SUPPORTED_REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException(
                    "Unsupported Rule v1 reasonCode: " + reasonCode
            );
        }
        if (!allowedReasonCodes.contains(reasonCode)) {
            throw new IllegalArgumentException(
                    "Rule v1 ruleCode " + ruleCode
                            + " is not compatible with reasonCode "
                            + reasonCode
            );
        }
    }

    public static boolean supportsReasonCode(String reasonCode) {
        return reasonCode != null
                && SUPPORTED_REASON_CODES.contains(reasonCode);
    }

    public static RuleAnalysisMetadata ruleAnalysisMetadata() {
        return RULE_ANALYSIS_METADATA;
    }

    public static String displayDescriptionFor(String reasonCode) {
        String description = DISPLAY_DESCRIPTIONS.get(reasonCode);
        if (description == null) {
            throw new IllegalArgumentException(
                    "Unsupported Rule v1 reasonCode: " + reasonCode
            );
        }
        return description;
    }

    public record RuleAnalysisMetadata(
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion
    ) {
    }
}
