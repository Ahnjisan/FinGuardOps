package com.aifds.backend.rule.client;

import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventType;
import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoreGroupId;
import com.aifds.backend.rule.client.dto.RuleScoreGroupSummaryResponse;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class RuleAnalysisResponseValidator {

    static final String SCORING_POLICY_VERSION = "scoring-policy-v1";

    private static final int WINDOW_SECONDS = 86_400;
    private static final Pattern RULE_SET_VERSION_PATTERN =
            Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CANONICAL_DECIMAL_PATTERN =
            Pattern.compile("^[1-9][0-9]*$");
    private static final Pattern CANONICAL_RULE_VERSION_PATTERN =
            Pattern.compile("^[1-9][0-9]*$");
    private static final Pattern UTC_Z_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"
    );

    private static final Map<RuleId, Set<String>> OBSERVATION_FIELDS = Map.of(
            RuleId.R001,
            Set.of("observedAmount", "amountThreshold"),
            RuleId.R002,
            Set.of(
                    "observedAmount",
                    "amountThreshold",
                    "eventId",
                    "deviceRegisteredAt",
                    "elapsedSeconds",
                    "windowSeconds"
            ),
            RuleId.R003,
            Set.of(
                    "observedAmount",
                    "amountThreshold",
                    "passwordChangedEventId",
                    "passwordChangedAt",
                    "transferLimitChangedEventId",
                    "transferLimitChangedAt",
                    "elapsedSeconds",
                    "windowSeconds"
            ),
            RuleId.R004,
            Set.of(
                    "observedAmount",
                    "eventId",
                    "beneficiaryRegisteredAt",
                    "elapsedSeconds",
                    "windowSeconds"
            )
    );

    public void validate(
            RuleAnalysisRequest request,
            RuleAnalysisResponse response
    ) {
        require(
                response.transactionId().equals(request.transaction().transactionId()),
                "transactionId does not match request"
        );
        require(
                response.analysis().evaluationCutoffAt()
                        .equals(request.evaluationCutoffAt()),
                "evaluationCutoffAt does not match request"
        );
        require(
                RULE_SET_VERSION_PATTERN.matcher(
                        response.analysis().ruleSetVersion()
                ).matches(),
                "ruleSetVersion is invalid"
        );
        String expectedRuleSetVersion =
                new CanonicalRuleSetVersionCalculator().calculate(
                        ruleVersionIdentities(request.ruleVersions())
                );
        require(
                expectedRuleSetVersion.equals(
                        response.analysis().ruleSetVersion()
                ),
                "ruleSetVersion does not match request"
        );

        List<ExpectedRule> expectedRules = expectedRules(request.ruleVersions());
        Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEvents =
                behaviorEventsById(request.behaviorEvents());
        validateScoring(
                expectedRules,
                response.analysis().scoringResult()
        );
        validateEvidence(
                request,
                expectedRules,
                behaviorEvents,
                response.analysis().scoringResult().ruleContributions(),
                response.analysis().evidence()
        );
    }

    private void validateScoring(
            List<ExpectedRule> expectedRules,
            RuleScoringResultResponse scoring
    ) {
        require(
                SCORING_POLICY_VERSION.equals(scoring.scoringPolicyVersion()),
                "scoringPolicyVersion is unsupported"
        );
        require(
                scoring.ruleContributions().size() == expectedRules.size(),
                "contribution count does not match request plan"
        );

        EnumMap<RuleScoreGroupId, Integer> rawScores =
                new EnumMap<>(RuleScoreGroupId.class);
        rawScores.put(RuleScoreGroupId.amount, 0);
        rawScores.put(RuleScoreGroupId.security, 0);

        Set<RuleId> seenRuleIds = new HashSet<>();
        Set<Integer> seenOrders = new HashSet<>();
        for (int index = 0; index < expectedRules.size(); index++) {
            ExpectedRule expected = expectedRules.get(index);
            RuleContributionResponse contribution =
                    scoring.ruleContributions().get(index);
            require(seenRuleIds.add(contribution.ruleId()), "duplicate contribution RuleId");
            require(
                    seenOrders.add(contribution.executionOrder()),
                    "duplicate contribution executionOrder"
            );
            require(
                    contribution.ruleId() == expected.contract().ruleId(),
                    "contribution RuleId does not match request plan"
            );
            require(
                    contribution.executionOrder() == expected.executionOrder(),
                    "contribution executionOrder does not match request plan"
            );
            int expectedContribution = contribution.matched()
                    ? expected.contract().weight()
                    : 0;
            require(
                    contribution.originalContribution() == expectedContribution,
                    "contribution value contradicts matched state"
            );
            rawScores.compute(
                    expected.contract().groupId(),
                    (ignored, current) -> current + contribution.originalContribution()
            );
        }
        requireMatchedPrerequisite(scoring.ruleContributions(), RuleId.R002);
        requireMatchedPrerequisite(scoring.ruleContributions(), RuleId.R003);

        require(scoring.groupSummaries().size() == 2, "group summary count is invalid");
        RuleScoreGroupSummaryResponse amount = scoring.groupSummaries().get(0);
        RuleScoreGroupSummaryResponse security = scoring.groupSummaries().get(1);
        validateGroupSummary(amount, RuleScoreGroupId.amount, 15, rawScores.get(
                RuleScoreGroupId.amount
        ));
        validateGroupSummary(security, RuleScoreGroupId.security, 60, rawScores.get(
                RuleScoreGroupId.security
        ));

        int expectedRiskScore = amount.appliedScore() + security.appliedScore();
        require(scoring.riskScore() == expectedRiskScore, "riskScore is inconsistent");
        require(scoring.riskScore() >= 0 && scoring.riskScore() <= 100,
                "riskScore is outside the supported range");
        require(
                scoring.riskLevel() == riskLevelFor(scoring.riskScore()),
                "riskLevel is inconsistent with riskScore"
        );
    }

    private void requireMatchedPrerequisite(
            List<RuleContributionResponse> contributions,
            RuleId dependentRule
    ) {
        boolean dependentMatched = contributions.stream()
                .anyMatch(value -> value.ruleId() == dependentRule && value.matched());
        if (!dependentMatched) {
            return;
        }
        require(
                contributions.stream().anyMatch(
                        value -> value.ruleId() == RuleId.R001 && value.matched()
                ),
                "dependent Rule matched without R001"
        );
    }

    private void validateGroupSummary(
            RuleScoreGroupSummaryResponse summary,
            RuleScoreGroupId expectedGroup,
            int expectedCap,
            int expectedRawScore
    ) {
        require(summary.groupId() == expectedGroup, "group summary order is invalid");
        require(summary.cap() == expectedCap, "group cap is invalid");
        require(summary.rawScore() == expectedRawScore, "group rawScore is invalid");
        int expectedApplied = Math.min(expectedCap, expectedRawScore);
        require(summary.appliedScore() == expectedApplied, "group appliedScore is invalid");
        require(
                summary.reduction() == expectedRawScore - expectedApplied,
                "group reduction is invalid"
        );
    }

    private void validateEvidence(
            RuleAnalysisRequest request,
            List<ExpectedRule> expectedRules,
            Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEvents,
            List<RuleContributionResponse> contributions,
            List<RuleEvidenceResponse> evidence
    ) {
        List<ExpectedRule> matchedRules = new ArrayList<>();
        Map<RuleId, RuleContributionResponse> contributionByRule =
                new EnumMap<>(RuleId.class);
        for (int index = 0; index < contributions.size(); index++) {
            RuleContributionResponse contribution = contributions.get(index);
            contributionByRule.put(contribution.ruleId(), contribution);
            if (contribution.matched()) {
                matchedRules.add(expectedRules.get(index));
            }
        }
        require(
                evidence.size() == matchedRules.size(),
                "Evidence completeness does not match contributions"
        );

        Set<RuleId> evidenceRuleIds = new HashSet<>();
        for (int index = 0; index < evidence.size(); index++) {
            RuleEvidenceResponse item = evidence.get(index);
            ExpectedRule expected = matchedRules.get(index);
            RuleContributionResponse contribution = contributionByRule.get(item.ruleId());
            require(evidenceRuleIds.add(item.ruleId()), "duplicate Evidence RuleId");
            require(item.ruleId() == expected.contract().ruleId(),
                    "Evidence RuleId or order does not match contributions");
            require(item.ruleVersionId().equals(expected.snapshot().ruleVersionId()),
                    "Evidence ruleVersionId does not match request");
            require(item.ruleCode().equals(expected.snapshot().ruleCode()),
                    "Evidence ruleCode does not match request");
            require(
                    CANONICAL_RULE_VERSION_PATTERN.matcher(item.ruleVersion()).matches()
                            && item.ruleVersion().equals(Integer.toString(
                            expected.snapshot().versionNumber()
                    )),
                    "Evidence ruleVersion does not match request"
            );
            require(item.reasonCode().equals(expected.snapshot().reasonCode()),
                    "Evidence reasonCode does not match request");
            require(item.executionOrder() == expected.executionOrder(),
                    "Evidence executionOrder does not match contribution");
            require(contribution != null && contribution.matched(),
                    "Evidence exists without a matched contribution");
            require(item.scoreContribution() == contribution.originalContribution(),
                    "Evidence contribution does not match scoring contribution");
            validateObservation(request, behaviorEvents, item);
        }
    }

    private void validateObservation(
            RuleAnalysisRequest request,
            Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEvents,
            RuleEvidenceResponse evidence
    ) {
        JsonNode observation = evidence.observationSummary();
        require(observation.isObject(), "observationSummary must be an object");
        Set<String> actualFields = new HashSet<>();
        observation.fieldNames().forEachRemaining(actualFields::add);
        require(
                OBSERVATION_FIELDS.get(evidence.ruleId()).equals(actualFields),
                "observationSummary fields are invalid"
        );
        requireDecimal(observation, "observedAmount");
        require(
                request.transaction().amount().equals(
                        observation.get("observedAmount").textValue()
                ),
                "observedAmount does not match request"
        );

        switch (evidence.ruleId()) {
            case R001 -> validateR001(request, evidence, observation);
            case R002 -> validateR002(request, behaviorEvents, evidence, observation);
            case R003 -> validateR003(request, behaviorEvents, evidence, observation);
            case R004 -> validateR004(request, behaviorEvents, evidence, observation);
        }
    }

    private void validateR001(
            RuleAnalysisRequest request,
            RuleEvidenceResponse evidence,
            JsonNode observation
    ) {
        requireThreshold(observation);
        require(
                evidence.evidenceOccurredAt().equals(request.evaluationCutoffAt()),
                "R001 evidenceOccurredAt is invalid"
        );
    }

    private void validateR002(
            RuleAnalysisRequest request,
            Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEvents,
            RuleEvidenceResponse evidence,
            JsonNode observation
    ) {
        requireThreshold(observation);
        UUID eventId = requireUuid(observation, "eventId");
        Instant eventTime = requireInstant(observation, "deviceRegisteredAt");
        RuleBehaviorEventSnapshotRequest event = requireEvent(
                behaviorEvents,
                eventId,
                RuleBehaviorEventType.DEVICE_REGISTERED,
                eventTime
        );
        require(event.deviceRef() != null, "R002 referenced event has no deviceRef");
        require(event.externalCustomerRef().equals(
                        request.transaction().externalCustomerRef()
                ),
                "R002 referenced event customer does not match transaction");
        require(event.deviceRef().equals(request.transaction().deviceRef()),
                "R002 referenced event device does not match transaction");
        validateElapsed(request.evaluationCutoffAt(), eventTime, observation);
        require(evidence.evidenceOccurredAt().equals(eventTime),
                "R002 evidenceOccurredAt is invalid");
    }

    private void validateR003(
            RuleAnalysisRequest request,
            Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEvents,
            RuleEvidenceResponse evidence,
            JsonNode observation
    ) {
        requireThreshold(observation);
        UUID passwordId = requireUuid(observation, "passwordChangedEventId");
        UUID limitId = requireUuid(observation, "transferLimitChangedEventId");
        require(!passwordId.equals(limitId), "R003 event IDs must be distinct");
        Instant passwordAt = requireInstant(observation, "passwordChangedAt");
        Instant limitAt = requireInstant(observation, "transferLimitChangedAt");
        RuleBehaviorEventSnapshotRequest passwordEvent = requireEvent(
                behaviorEvents,
                passwordId,
                RuleBehaviorEventType.PASSWORD_CHANGED,
                passwordAt
        );
        RuleBehaviorEventSnapshotRequest limitEvent = requireEvent(
                behaviorEvents,
                limitId,
                RuleBehaviorEventType.TRANSFER_LIMIT_CHANGED,
                limitAt
        );
        require(passwordEvent.externalCustomerRef().equals(
                        request.transaction().externalCustomerRef()
                ),
                "R003 password event customer does not match transaction");
        require(limitEvent.externalCustomerRef().equals(
                        request.transaction().externalCustomerRef()
                ),
                "R003 limit event customer does not match transaction");
        require(limitEvent.accountRef() != null
                        && limitEvent.accountRef().equals(
                        request.transaction().senderAccountRef()
                ),
                "R003 limit event account does not match transaction");
        require(!passwordAt.isAfter(limitAt), "R003 event order is invalid");
        validateElapsed(request.evaluationCutoffAt(), limitAt, observation);
        require(
                Duration.between(passwordAt, request.evaluationCutoffAt()).getSeconds()
                        <= WINDOW_SECONDS,
                "R003 password event is outside the supported window"
        );
        require(evidence.evidenceOccurredAt().equals(limitAt),
                "R003 evidenceOccurredAt is invalid");
    }

    private void validateR004(
            RuleAnalysisRequest request,
            Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEvents,
            RuleEvidenceResponse evidence,
            JsonNode observation
    ) {
        UUID eventId = requireUuid(observation, "eventId");
        Instant eventTime = requireInstant(observation, "beneficiaryRegisteredAt");
        RuleBehaviorEventSnapshotRequest event = requireEvent(
                behaviorEvents,
                eventId,
                RuleBehaviorEventType.BENEFICIARY_REGISTERED,
                eventTime
        );
        require(event.beneficiaryRef() != null, "R004 referenced event has no beneficiaryRef");
        require(event.externalCustomerRef().equals(
                        request.transaction().externalCustomerRef()
                ),
                "R004 referenced event customer does not match transaction");
        require(event.accountRef() != null
                        && event.accountRef().equals(
                        request.transaction().senderAccountRef()
                ),
                "R004 referenced event account does not match transaction");
        require(event.beneficiaryRef().equals(
                        request.transaction().recipientAccountRef()
                ),
                "R004 referenced event beneficiary does not match transaction");
        validateElapsed(request.evaluationCutoffAt(), eventTime, observation);
        require(evidence.evidenceOccurredAt().equals(eventTime),
                "R004 evidenceOccurredAt is invalid");
    }

    private void validateElapsed(
            Instant cutoff,
            Instant eventTime,
            JsonNode observation
    ) {
        int elapsedSeconds = requireInteger(observation, "elapsedSeconds", 0);
        int windowSeconds = requireInteger(observation, "windowSeconds", 1);
        require(windowSeconds == WINDOW_SECONDS, "windowSeconds is unsupported");
        require(!eventTime.isAfter(cutoff), "Evidence event occurs after cutoff");
        require(
                elapsedSeconds == Duration.between(eventTime, cutoff).getSeconds(),
                "elapsedSeconds is inconsistent"
        );
        require(elapsedSeconds <= windowSeconds, "Evidence event is outside windowSeconds");
    }

    private RuleBehaviorEventSnapshotRequest requireEvent(
            Map<UUID, RuleBehaviorEventSnapshotRequest> events,
            UUID eventId,
            RuleBehaviorEventType expectedType,
            Instant expectedTime
    ) {
        RuleBehaviorEventSnapshotRequest event = events.get(eventId);
        require(event != null, "Evidence eventId is absent from request snapshot");
        require(event.eventType() == expectedType, "Evidence event type is invalid");
        require(event.occurredAt().equals(expectedTime), "Evidence event time is invalid");
        return event;
    }

    private void requireThreshold(JsonNode observation) {
        requireDecimal(observation, "amountThreshold");
        require(
                "10000000".equals(observation.get("amountThreshold").textValue()),
                "amountThreshold is unsupported"
        );
    }

    private void requireDecimal(JsonNode root, String field) {
        JsonNode value = root.get(field);
        require(
                value != null
                        && value.isTextual()
                        && CANONICAL_DECIMAL_PATTERN.matcher(value.textValue()).matches(),
                field + " is not a canonical decimal string"
        );
    }

    private UUID requireUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        require(value != null && value.isTextual(), field + " must be a UUID string");
        try {
            UUID parsed = UUID.fromString(value.textValue());
            require(parsed.toString().equals(value.textValue()), field + " must be canonical");
            require(parsed.version() == 4 && parsed.variant() == 2,
                    field + " must be RFC 4122 UUID v4");
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must be a canonical UUID v4");
        }
    }

    private Instant requireInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        require(value != null && value.isTextual(), field + " must be a timestamp string");
        require(
                UTC_Z_PATTERN.matcher(value.textValue()).matches(),
                field + " must use the strict UTC Z timestamp format"
        );
        try {
            return Instant.parse(value.textValue());
        } catch (RuntimeException exception) {
            throw invalid(field + " must be a UTC timestamp");
        }
    }

    private int requireInteger(JsonNode root, String field, int minimum) {
        JsonNode value = root.get(field);
        require(
                value != null
                        && value.isIntegralNumber()
                        && value.canConvertToInt()
                        && value.intValue() >= minimum,
                field + " must be an integer in the supported range"
        );
        return value.intValue();
    }

    private List<ExpectedRule> expectedRules(
            List<RuleVersionSnapshotRequest> snapshots
    ) {
        require(!snapshots.isEmpty(), "request contains no RuleVersion snapshot");
        Map<UUID, RuleVersionSnapshotRequest> snapshotsById = new HashMap<>();
        for (RuleVersionSnapshotRequest snapshot : snapshots) {
            require(
                    snapshotsById.put(snapshot.ruleVersionId(), snapshot) == null,
                    "request contains a duplicate RuleVersion"
            );
            RuleV1ExecutionPlanRegistry.requireExecutionCompatible(
                    snapshot.ruleCode(),
                    snapshot.reasonCode(),
                    snapshot.weight(),
                    snapshot.conditionDefinition()
            );
        }
        return RuleV1ExecutionPlanRegistry.canonicalize(
                        ruleVersionIdentities(snapshots)
                ).stream()
                .map(rule -> new ExpectedRule(
                        snapshotsById.get(rule.identity().ruleVersionId()),
                        toRuleContract(rule.capability()),
                        rule.executionOrder()
                ))
                .toList();
    }

    private List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity>
    ruleVersionIdentities(List<RuleVersionSnapshotRequest> snapshots) {
        return snapshots.stream()
                .map(snapshot -> new RuleV1ExecutionPlanRegistry
                        .RuleVersionIdentity(
                        snapshot.fraudRuleId(),
                        snapshot.ruleVersionId(),
                        snapshot.ruleCode(),
                        snapshot.versionNumber()
                ))
                .toList();
    }

    private RuleContract toRuleContract(
            RuleV1ExecutionPlanRegistry.RuleCapability capability
    ) {
        RuleScoreGroupId groupId = switch (capability.scoreGroup()) {
            case AMOUNT -> RuleScoreGroupId.amount;
            case SECURITY -> RuleScoreGroupId.security;
        };
        return new RuleContract(
                RuleId.valueOf(capability.ruleId().name()),
                capability.reasonCode(),
                capability.weight(),
                groupId
        );
    }

    private Map<UUID, RuleBehaviorEventSnapshotRequest> behaviorEventsById(
            List<RuleBehaviorEventSnapshotRequest> events
    ) {
        Map<UUID, RuleBehaviorEventSnapshotRequest> byId = new HashMap<>();
        for (RuleBehaviorEventSnapshotRequest event : events) {
            require(byId.put(event.eventId(), event) == null,
                    "request contains duplicate behavior event IDs");
        }
        return Map.copyOf(byId);
    }

    private RuleRiskLevel riskLevelFor(int riskScore) {
        if (riskScore < 20) {
            return RuleRiskLevel.LOW;
        }
        if (riskScore < 50) {
            return RuleRiskLevel.MEDIUM;
        }
        if (riskScore < 80) {
            return RuleRiskLevel.HIGH;
        }
        return RuleRiskLevel.CRITICAL;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record RuleContract(
            RuleId ruleId,
            String reasonCode,
            int weight,
            RuleScoreGroupId groupId
    ) {
    }

    private record ExpectedRule(
            RuleVersionSnapshotRequest snapshot,
            RuleContract contract,
            int executionOrder
    ) {
    }
}
