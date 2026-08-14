package com.aifds.backend.rule.client;

import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleAnalysisResultResponse;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventType;
import com.aifds.backend.rule.client.dto.RuleContributionResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoreGroupId;
import com.aifds.backend.rule.client.dto.RuleScoreGroupSummaryResponse;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.rule.client.dto.RuleTransactionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleTransactionType;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleVersionStatus;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class RuleAnalysisClientTestFixtures {

    static final String TRACE_ID = "trace_rule_client_0001";
    static final Instant CUTOFF = Instant.parse("2026-07-23T12:00:00Z");
    static final UUID TRANSACTION_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001"
    );
    static final UUID R001_VERSION_ID = UUID.fromString(
            "20000000-0000-4000-8000-000000000001"
    );
    static final UUID R004_VERSION_ID = UUID.fromString(
            "20000000-0000-4000-8000-000000000004"
    );
    static final UUID BENEFICIARY_EVENT_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000004"
    );
    static final String RULE_SET_VERSION =
            "659019c2294c4cfaa3d68f8cb312418e71c2cad5071aec360331422a259a4e63";

    private RuleAnalysisClientTestFixtures() {
    }

    static RuleAnalysisRequest request(ObjectMapper mapper) {
        RuleTransactionSnapshotRequest transaction =
                new RuleTransactionSnapshotRequest(
                        TRANSACTION_ID,
                        RuleTransactionType.ACCOUNT_TRANSFER,
                        "12000000",
                        "KRW",
                        CUTOFF,
                        "customer_sensitive_ref",
                        "sender_sensitive_ref",
                        "recipient_sensitive_ref",
                        "device_sensitive_ref"
                );
        RuleBehaviorEventSnapshotRequest event =
                new RuleBehaviorEventSnapshotRequest(
                        BENEFICIARY_EVENT_ID,
                        RuleBehaviorEventType.BENEFICIARY_REGISTERED,
                        CUTOFF.minusSeconds(60),
                        "customer_sensitive_ref",
                        "sender_sensitive_ref",
                        null,
                        "recipient_sensitive_ref"
                );
        return new RuleAnalysisRequest(
                CUTOFF,
                transaction,
                List.of(event),
                List.of(r001(mapper), r004(mapper))
        );
    }

    static RuleAnalysisResponse matchedResponse(ObjectMapper mapper) {
        RuleScoringResultResponse scoring = new RuleScoringResultResponse(
                RuleV1ContractRegistry.ruleAnalysisMetadata()
                        .scoringPolicyVersion(),
                25,
                RuleRiskLevel.MEDIUM,
                List.of(
                        new RuleContributionResponse(RuleId.R001, 1, true, 15),
                        new RuleContributionResponse(RuleId.R004, 2, true, 10)
                ),
                List.of(
                        new RuleScoreGroupSummaryResponse(
                                RuleScoreGroupId.amount,
                                15,
                                15,
                                15,
                                0
                        ),
                        new RuleScoreGroupSummaryResponse(
                                RuleScoreGroupId.security,
                                10,
                                60,
                                10,
                                0
                        )
                )
        );
        return response(
                scoring,
                List.of(r001Evidence(mapper), r004Evidence(mapper))
        );
    }

    static RuleAnalysisResponse unmatchedResponse() {
        RuleScoringResultResponse scoring = new RuleScoringResultResponse(
                RuleV1ContractRegistry.ruleAnalysisMetadata()
                        .scoringPolicyVersion(),
                0,
                RuleRiskLevel.LOW,
                List.of(
                        new RuleContributionResponse(RuleId.R001, 1, false, 0),
                        new RuleContributionResponse(RuleId.R004, 2, false, 0)
                ),
                List.of(
                        new RuleScoreGroupSummaryResponse(
                                RuleScoreGroupId.amount,
                                0,
                                15,
                                0,
                                0
                        ),
                        new RuleScoreGroupSummaryResponse(
                                RuleScoreGroupId.security,
                                0,
                                60,
                                0,
                                0
                        )
                )
        );
        return response(scoring, List.of());
    }

    static RuleAnalysisResponse response(
            RuleScoringResultResponse scoring,
            List<RuleEvidenceResponse> evidence
    ) {
        return new RuleAnalysisResponse(
                TRANSACTION_ID,
                TRACE_ID,
                new RuleAnalysisResultResponse(
                        CUTOFF,
                        RULE_SET_VERSION,
                        scoring,
                        evidence
                )
        );
    }

    private static RuleVersionSnapshotRequest r001(ObjectMapper mapper) {
        ObjectNode condition = mapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        condition.put("currencyCode", "KRW");
        condition.put("amountThreshold", "10000000");
        return new RuleVersionSnapshotRequest(
                UUID.fromString("11000000-0000-4000-8000-000000000001"),
                "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                RuleLifecycleStatus.ACTIVE,
                R001_VERSION_ID,
                1,
                RuleVersionStatus.PUBLISHED,
                "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                15,
                condition,
                CUTOFF.minusSeconds(3600),
                null
        );
    }

    private static RuleVersionSnapshotRequest r004(ObjectMapper mapper) {
        ObjectNode condition = mapper.createObjectNode()
                .put("eventType", "BENEFICIARY_REGISTERED")
                .put("windowSeconds", 86_400)
                .put(
                        "matchPolicy",
                        "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY"
                )
                .put(
                        "selectionPolicy",
                        "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                );
        return new RuleVersionSnapshotRequest(
                UUID.fromString("11000000-0000-4000-8000-000000000004"),
                "RECENT_BENEFICIARY_TRANSFER",
                RuleLifecycleStatus.ACTIVE,
                R004_VERSION_ID,
                1,
                RuleVersionStatus.PUBLISHED,
                "RECENT_BENEFICIARY_TRANSFER",
                10,
                condition,
                CUTOFF.minusSeconds(3600),
                null
        );
    }

    private static RuleEvidenceResponse r001Evidence(ObjectMapper mapper) {
        ObjectNode observation = mapper.createObjectNode()
                .put("observedAmount", "12000000")
                .put("amountThreshold", "10000000");
        return new RuleEvidenceResponse(
                RuleId.R001,
                R001_VERSION_ID,
                "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                "1",
                "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                1,
                15,
                observation,
                CUTOFF
        );
    }

    private static RuleEvidenceResponse r004Evidence(ObjectMapper mapper) {
        ObjectNode observation = mapper.createObjectNode()
                .put("observedAmount", "12000000")
                .put("eventId", BENEFICIARY_EVENT_ID.toString())
                .put("beneficiaryRegisteredAt", CUTOFF.minusSeconds(60).toString())
                .put("elapsedSeconds", 60)
                .put("windowSeconds", 86_400);
        return new RuleEvidenceResponse(
                RuleId.R004,
                R004_VERSION_ID,
                "RECENT_BENEFICIARY_TRANSFER",
                "1",
                "RECENT_BENEFICIARY_TRANSFER",
                2,
                10,
                observation,
                CUTOFF.minusSeconds(60)
        );
    }
}
