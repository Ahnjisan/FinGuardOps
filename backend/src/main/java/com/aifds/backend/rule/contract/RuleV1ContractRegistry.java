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
    public static final String EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT =
            "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT";
    public static final String CUSTOMER_BASELINE_HIGH_AMOUNT =
            "CUSTOMER_BASELINE_HIGH_AMOUNT";
    public static final String EXTERNAL_RISK_DEVICE_MATCH =
            "EXTERNAL_RISK_DEVICE_MATCH";
    public static final String MOCK_APP_SECURITY_ALERT =
            "MOCK_APP_SECURITY_ALERT";
    public static final String MATERIAL_LIMIT_UPLIFT =
            "MATERIAL_LIMIT_UPLIFT";
    public static final String MOCK_OVERSEAS_NETWORK =
            "MOCK_OVERSEAS_NETWORK";
    public static final String DISTRIBUTED_OUTBOUND_WINDOW =
            "DISTRIBUTED_OUTBOUND_WINDOW";
    public static final String MANY_CUSTOMER_INBOUND_WINDOW =
            "MANY_CUSTOMER_INBOUND_WINDOW";
    public static final String VERIFIED_INBOUND_ATM_DRAIN =
            "VERIFIED_INBOUND_ATM_DRAIN";
    public static final String VERIFIED_LOAN_NEW_RECIPIENT_DRAIN =
            "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN";
    public static final String VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND =
            "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND";

    private static final Map<String, Set<String>> ALLOWED_REASON_CODES =
            Map.ofEntries(
                    Map.entry(TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                    Set.of(TRANSFER_ABSOLUTE_HIGH_AMOUNT)),
                    Map.entry(RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                    Set.of(RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT)),
                    Map.entry(RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                    Set.of(RECENT_SECURITY_CHANGE_HIGH_AMOUNT)),
                    Map.entry(RECENT_BENEFICIARY_TRANSFER,
                    Set.of(RECENT_BENEFICIARY_TRANSFER)),
                    Map.entry(EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT,
                    Set.of(EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT)),
                    Map.entry(CUSTOMER_BASELINE_HIGH_AMOUNT, Set.of(CUSTOMER_BASELINE_HIGH_AMOUNT)),
                    Map.entry(EXTERNAL_RISK_DEVICE_MATCH, Set.of(EXTERNAL_RISK_DEVICE_MATCH)),
                    Map.entry(MOCK_APP_SECURITY_ALERT, Set.of(MOCK_APP_SECURITY_ALERT)),
                    Map.entry(MATERIAL_LIMIT_UPLIFT, Set.of(MATERIAL_LIMIT_UPLIFT)),
                    Map.entry(MOCK_OVERSEAS_NETWORK, Set.of(MOCK_OVERSEAS_NETWORK)),
                    Map.entry(DISTRIBUTED_OUTBOUND_WINDOW, Set.of(DISTRIBUTED_OUTBOUND_WINDOW)),
                    Map.entry(MANY_CUSTOMER_INBOUND_WINDOW, Set.of(MANY_CUSTOMER_INBOUND_WINDOW)),
                    Map.entry(VERIFIED_INBOUND_ATM_DRAIN, Set.of(VERIFIED_INBOUND_ATM_DRAIN)),
                    Map.entry(VERIFIED_LOAN_NEW_RECIPIENT_DRAIN,
                            Set.of(VERIFIED_LOAN_NEW_RECIPIENT_DRAIN)),
                    Map.entry(VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND,
                            Set.of(VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND))
            );
    private static final Set<String> SUPPORTED_REASON_CODES = Set.of(
            TRANSFER_ABSOLUTE_HIGH_AMOUNT,
            RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
            RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
            RECENT_BENEFICIARY_TRANSFER,
            EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT,
            CUSTOMER_BASELINE_HIGH_AMOUNT,
            EXTERNAL_RISK_DEVICE_MATCH,
            MOCK_APP_SECURITY_ALERT,
            MATERIAL_LIMIT_UPLIFT,
            MOCK_OVERSEAS_NETWORK,
            DISTRIBUTED_OUTBOUND_WINDOW,
            MANY_CUSTOMER_INBOUND_WINDOW,
            VERIFIED_INBOUND_ATM_DRAIN,
            VERIFIED_LOAN_NEW_RECIPIENT_DRAIN,
            VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND
    );
    private static final Map<String, String> DISPLAY_DESCRIPTIONS = Map.ofEntries(
            Map.entry(TRANSFER_ABSOLUTE_HIGH_AMOUNT, "절대 고액 이체"),
            Map.entry(RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                    "최근 기기 등록 이벤트가 있는 고액 이체"),
            Map.entry(RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                    "최근 보안정보 변경 시퀀스가 있는 고액 이체"),
            Map.entry(RECENT_BENEFICIARY_TRANSFER, "최근 등록 수취인 이체"),
            Map.entry(EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT, "외부 위험 수취 계좌 일치"),
            Map.entry(CUSTOMER_BASELINE_HIGH_AMOUNT, "고객 기준선 대비 고액 이체"),
            Map.entry(EXTERNAL_RISK_DEVICE_MATCH, "외부 위험 기기 일치"),
            Map.entry(MOCK_APP_SECURITY_ALERT, "Mock 앱 보안 위험 신호"),
            Map.entry(MATERIAL_LIMIT_UPLIFT, "큰 폭의 한도 상향"),
            Map.entry(MOCK_OVERSEAS_NETWORK, "Mock 해외 접속 신호"),
            Map.entry(DISTRIBUTED_OUTBOUND_WINDOW, "단시간 다수 수취 계좌 분산 송금"),
            Map.entry(MANY_CUSTOMER_INBOUND_WINDOW, "다수 고객의 동일 계좌 집금"),
            Map.entry(VERIFIED_INBOUND_ATM_DRAIN, "검증된 입금 후 ATM 인출"),
            Map.entry(VERIFIED_LOAN_NEW_RECIPIENT_DRAIN,
                    "검증된 대출금의 신규 수취인 송금"),
            Map.entry(VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND,
                    "검증된 오픈뱅킹 다계좌 집중 후 재송금")
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
