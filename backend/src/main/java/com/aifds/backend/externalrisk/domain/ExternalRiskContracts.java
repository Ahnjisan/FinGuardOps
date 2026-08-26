package com.aifds.backend.externalrisk.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ExternalRiskContracts {

    private static final Pattern REFERENCE = Pattern.compile("^\\S(?:.*\\S)?$|^\\S$");
    private static final Pattern TRACE_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$"
    );
    private static final Pattern PROVIDER_CODE = Pattern.compile(
            "^[A-Z][A-Z0-9_]{0,63}$"
    );
    private static final int MAX_MATCHES = 3;

    private ExternalRiskContracts() {
    }

    public static boolean isUuidV4(UUID value) {
        return value != null && value.version() == 4 && value.variant() == 2;
    }

    static boolean isMicrosecondInstant(Instant value) {
        return value != null && value.getNano() % 1_000 == 0;
    }

    static boolean isReference(String value) {
        return value != null
                && value.length() <= 128
                && REFERENCE.matcher(value).matches();
    }

    static boolean isOptionalReference(String value) {
        return value == null || isReference(value);
    }

    public static boolean isTraceId(String value) {
        return value != null && TRACE_ID.matcher(value).matches();
    }

    public static boolean isProviderCode(String value) {
        return value != null && PROVIDER_CODE.matcher(value).matches();
    }

    public static List<ExternalRiskMatch> copyProviderResponseMatches(
            List<ExternalRiskMatch> matches
    ) {
        if (matches == null) {
            throw invalidResponse();
        }
        int size = matches.size();
        if (size > MAX_MATCHES) {
            throw invalidResponse();
        }
        for (int index = 0; index < size; index++) {
            if (matches.get(index) == null) {
                throw invalidResponse();
            }
        }
        return List.copyOf(matches);
    }

    public static boolean hasValidUniqueMatches(
            List<ExternalRiskMatch> matches
    ) {
        if (matches == null) {
            return false;
        }
        int size = matches.size();
        if (size > MAX_MATCHES) {
            return false;
        }
        Set<ExternalRiskMatch> uniqueMatches = new HashSet<>();
        for (int index = 0; index < size; index++) {
            ExternalRiskMatch match = matches.get(index);
            if (!isSupportedMatch(match) || !uniqueMatches.add(match)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedMatch(ExternalRiskMatch match) {
        if (match == null) {
            return false;
        }
        return isMatch(
                match,
                ExternalRiskSubjectType.SENDER_ACCOUNT,
                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
        ) || isMatch(
                match,
                ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT
        ) || isMatch(
                match,
                ExternalRiskSubjectType.DEVICE,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        );
    }

    private static boolean isMatch(
            ExternalRiskMatch match,
            ExternalRiskSubjectType subjectType,
            ExternalRiskType riskType,
            ExternalRiskReasonCode reasonCode
    ) {
        return match.subjectType() == subjectType
                && match.riskType() == riskType
                && match.reasonCode() == reasonCode;
    }

    private static ExternalRiskLookupException invalidResponse() {
        return new ExternalRiskLookupException(
                ExternalRiskFailureCategory.INVALID_RESPONSE
        );
    }
}
