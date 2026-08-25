package com.aifds.backend.rule.client;

import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.rule.client.dto.ExternalRiskMatchRequest;
import com.aifds.backend.rule.client.dto.ExternalRiskSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RuleAnalysisRequestV2Mapper {

    private static final Map<ExternalRiskSubjectType, Integer> SUBJECT_ORDER = Map.of(
            ExternalRiskSubjectType.SENDER_ACCOUNT, 0,
            ExternalRiskSubjectType.RECIPIENT_ACCOUNT, 1,
            ExternalRiskSubjectType.DEVICE, 2
    );
    private static final Map<ExternalRiskType, Integer> RISK_TYPE_ORDER = Map.of(
            ExternalRiskType.SUSPICIOUS_ACCOUNT, 0,
            ExternalRiskType.RISK_DEVICE, 1
    );
    private static final Map<ExternalRiskReasonCode, Integer> REASON_ORDER = Map.of(
            ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT, 0,
            ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT, 1,
            ExternalRiskReasonCode.RISK_DEVICE, 2
    );
    private static final Comparator<ExternalRiskMatch> MATCH_ORDER = Comparator
            .comparingInt((ExternalRiskMatch match) -> rank(
                    SUBJECT_ORDER,
                    match.subjectType()
            ))
            .thenComparingInt(match -> rank(RISK_TYPE_ORDER, match.riskType()))
            .thenComparingInt(match -> rank(REASON_ORDER, match.reasonCode()));

    public RuleAnalysisRequestV2 map(
            RuleAnalysisRequest request,
            ExternalRiskSnapshot snapshot
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        require(
                request.evaluationCutoffAt()
                        .equals(request.transaction().occurredAt()),
                "request evaluationCutoffAt must match transaction occurredAt"
        );
        require(
                request.transaction().transactionId().equals(snapshot.transactionId()),
                "snapshot transactionId must match request"
        );
        require(
                request.evaluationCutoffAt().equals(snapshot.evaluationCutoffAt()),
                "snapshot evaluationCutoffAt must match request"
        );
        requireMicrosecond(snapshot.providerAsOf(), "providerAsOf");
        requireMicrosecond(snapshot.lookedUpAt(), "lookedUpAt");
        Instant cutoff = request.evaluationCutoffAt();
        require(
                !snapshot.providerAsOf().isAfter(cutoff),
                "providerAsOf must not be after evaluationCutoffAt"
        );
        require(
                !snapshot.lookedUpAt().isBefore(cutoff),
                "lookedUpAt must not be before evaluationCutoffAt"
        );
        require(
                snapshot.lookupStatus() == ExternalRiskLookupStatus.SUCCEEDED,
                "lookupStatus must be SUCCEEDED"
        );

        List<ExternalRiskMatch> matches = copyAndValidateMatches(snapshot);
        matches.sort(MATCH_ORDER);
        List<ExternalRiskMatchRequest> wireMatches = matches.stream()
                .map(match -> new ExternalRiskMatchRequest(
                        match.subjectType(),
                        match.riskType(),
                        match.reasonCode()
                ))
                .toList();
        ExternalRiskSnapshotRequest externalRisk = new ExternalRiskSnapshotRequest(
                snapshot.providerCode(),
                snapshot.lookupStatus(),
                snapshot.policyResult(),
                snapshot.providerAsOf(),
                snapshot.lookedUpAt(),
                wireMatches
        );
        return new RuleAnalysisRequestV2(
                request.evaluationCutoffAt(),
                request.transaction(),
                request.behaviorEvents(),
                request.ruleVersions(),
                externalRisk
        );
    }

    private List<ExternalRiskMatch> copyAndValidateMatches(
            ExternalRiskSnapshot snapshot
    ) {
        List<ExternalRiskMatch> source = Objects.requireNonNull(
                snapshot.matches(),
                "matches must not be null"
        );
        require(source.size() <= 3, "matches must contain at most 3 items");
        List<ExternalRiskMatch> matches = new ArrayList<>(source.size());
        Set<ExternalRiskMatch> unique = new HashSet<>();
        for (ExternalRiskMatch match : source) {
            Objects.requireNonNull(match, "matches must not contain null");
            require(isSupportedMatch(match), "match combination is unsupported");
            require(unique.add(match), "matches must not contain duplicates");
            matches.add(match);
        }
        ExternalRiskPolicyResult expected = matches.isEmpty()
                ? ExternalRiskPolicyResult.UNMATCHED
                : ExternalRiskPolicyResult.MATCHED;
        require(
                snapshot.policyResult() == expected,
                "policyResult is inconsistent with matches"
        );
        return matches;
    }

    private boolean isSupportedMatch(ExternalRiskMatch match) {
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

    private boolean isMatch(
            ExternalRiskMatch match,
            ExternalRiskSubjectType subjectType,
            ExternalRiskType riskType,
            ExternalRiskReasonCode reasonCode
    ) {
        return match.subjectType() == subjectType
                && match.riskType() == riskType
                && match.reasonCode() == reasonCode;
    }

    private static <T> int rank(Map<T, Integer> ranks, T value) {
        Integer rank = ranks.get(value);
        if (rank == null) {
            throw new IllegalArgumentException("match enum value is unsupported");
        }
        return rank;
    }

    private void requireMicrosecond(Instant value, String field) {
        require(
                value != null && value.getNano() % 1_000 == 0,
                field + " precision must not exceed microseconds"
        );
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
