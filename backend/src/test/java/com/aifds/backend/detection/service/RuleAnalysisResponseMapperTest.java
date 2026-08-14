package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleAnalysisResultResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleAnalysisResponseMapperTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001"
    );
    private static final Instant CUTOFF = Instant.parse(
            "2026-08-14T01:00:00Z"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleAnalysisResponseMapper mapper =
            new RuleAnalysisResponseMapper();

    @ParameterizedTest
    @EnumSource(RuleRiskLevel.class)
    void mapsEveryRiskLevelExhaustively(RuleRiskLevel source) {
        RuleAnalysisResponseMapper.MappedRuleAnalysisResult mapped =
                mapper.map(response(source, List.of()));

        assertThat(mapped.riskLevel()).isEqualTo(
                RiskLevel.valueOf(source.name())
        );
    }

    @Test
    void mapsR001ThroughR004EvidenceUsingIndexOrderAndDescriptions() {
        List<RuleEvidenceResponse> evidence = List.of(
                evidence(
                        RuleId.R001,
                        "20000000-0000-4000-8000-000000000001",
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        1
                ),
                evidence(
                        RuleId.R002,
                        "20000000-0000-4000-8000-000000000002",
                        RuleV1ContractRegistry
                                .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                        3
                ),
                evidence(
                        RuleId.R003,
                        "20000000-0000-4000-8000-000000000003",
                        RuleV1ContractRegistry
                                .RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                        7
                ),
                evidence(
                        RuleId.R004,
                        "20000000-0000-4000-8000-000000000004",
                        RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                        11
                )
        );

        RuleAnalysisResponseMapper.MappedRuleAnalysisResult mapped =
                mapper.map(response(RuleRiskLevel.HIGH, evidence));

        assertThat(mapped.evidenceDrafts())
                .extracting(RuleEvidenceDraft::sortOrder)
                .containsExactly(0, 1, 2, 3);
        assertThat(mapped.evidenceDrafts())
                .extracting(RuleEvidenceDraft::ruleVersionId)
                .containsExactlyElementsOf(
                        evidence.stream()
                                .map(RuleEvidenceResponse::ruleVersionId)
                                .toList()
                );
        assertThat(mapped.evidenceDrafts())
                .extracting(RuleEvidenceDraft::displayDescription)
                .containsExactly(
                        "절대 고액 이체",
                        "최근 기기 등록 이벤트가 있는 고액 이체",
                        "최근 보안정보 변경 시퀀스가 있는 고액 이체",
                        "최근 등록 수취인 이체"
                );
        assertThat(mapped.evidenceDrafts())
                .extracting(RuleEvidenceDraft::evidenceOccurredAt)
                .containsOnly(CUTOFF);
    }

    @Test
    void defensivelyCopiesObservationSummaryInBothDirections() {
        ObjectNode original = objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000");
        RuleEvidenceResponse item = new RuleEvidenceResponse(
                RuleId.R001,
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                "1",
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                1,
                15,
                original,
                CUTOFF
        );

        RuleEvidenceDraft draft = mapper.map(response(
                RuleRiskLevel.MEDIUM,
                List.of(item)
        )).evidenceDrafts().get(0);
        original.put("observedAmount", "99999999");
        ObjectNode exposed = (ObjectNode) draft.observationSummary();
        exposed.put("observedAmount", "1");

        assertThat(draft.observationSummary().get("observedAmount").asText())
                .isEqualTo("10000000");
        assertThat(item.observationSummary().get("observedAmount").asText())
                .isEqualTo("10000000");
    }

    @Test
    void allowsValidatedEmptyEvidence() {
        RuleAnalysisResponseMapper.MappedRuleAnalysisResult mapped =
                mapper.map(response(RuleRiskLevel.LOW, List.of()));

        assertThat(mapped.riskScore()).isZero();
        assertThat(mapped.evidenceDrafts()).isEmpty();
        assertThatThrownBy(() -> mapped.evidenceDrafts().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnsupportedReasonCode() {
        RuleEvidenceResponse unsupported = evidence(
                RuleId.R001,
                "20000000-0000-4000-8000-000000000001",
                "UNSUPPORTED_REASON",
                1
        );

        assertThatThrownBy(() -> mapper.map(response(
                RuleRiskLevel.LOW,
                List.of(unsupported)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_REASON");
    }

    private RuleAnalysisResponse response(
            RuleRiskLevel riskLevel,
            List<RuleEvidenceResponse> evidence
    ) {
        int riskScore = riskLevel == RuleRiskLevel.LOW ? 0 : 55;
        return new RuleAnalysisResponse(
                TRANSACTION_ID,
                "trace_rule_mapper_01",
                new RuleAnalysisResultResponse(
                        CUTOFF,
                        "a".repeat(64),
                        new RuleScoringResultResponse(
                                "scoring-policy-v1",
                                riskScore,
                                riskLevel,
                                List.of(),
                                List.of()
                        ),
                        evidence
                )
        );
    }

    private RuleEvidenceResponse evidence(
            RuleId ruleId,
            String ruleVersionId,
            String reasonCode,
            int executionOrder
    ) {
        return new RuleEvidenceResponse(
                ruleId,
                UUID.fromString(ruleVersionId),
                reasonCode,
                "1",
                reasonCode,
                executionOrder,
                15,
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000"),
                CUTOFF
        );
    }
}
