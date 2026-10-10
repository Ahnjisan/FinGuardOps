package com.aifds.backend.scenario;

import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioCaseCorrelationServiceTest {
    @Mock private DetectionEvidenceRepository evidence;
    @Mock private FraudCaseRepository cases;
    @Mock private JdbcTemplate jdbc;
    @Mock private FinancialTransaction transaction;
    @Mock private DetectionResult result;
    private ScenarioCaseCorrelationService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioCaseCorrelationService(evidence, cases, jdbc);
    }

    private void bind(List<DetectionEvidence> rows) {
        when(transaction.getAdoptedDetectionResult()).thenReturn(result);
        when(result.getScoringPolicyVersion()).thenReturn("rule-ml-policy-v3");
        when(transaction.getRecipientAccountRef()).thenReturn("recipient");
        when(result.getId()).thenReturn(21L);
        when(evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                21L, DetectionEvidenceType.RULE)).thenReturn(rows);
    }

    private DetectionEvidence row(String code, String field, UUID id) {
        DetectionEvidence row = org.mockito.Mockito.mock(DetectionEvidence.class);
        when(row.getRuleCode()).thenReturn(code);
        if (field != null) when(row.getObservationSummary()).thenReturn(
                JsonNodeFactory.instance.objectNode().put(field, id.toString()));
        return row;
    }

    @Test
    void r005MatchForcesSeparateCaseEvenWhenScenarioSequenceAlsoMatches() {
        bind(List.of(row(RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                        null, null),
                row(RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT,
                        null, null)));
        assertThat(service.correlation(transaction)).isEmpty();
    }

    @Test
    void twoScenariosChooseSecondAndDifferentAnchorsCannotMerge() {
        UUID password = UUID.randomUUID();
        UUID limit = UUID.randomUUID();
        DetectionEvidence second = org.mockito.Mockito.mock(DetectionEvidence.class);
        when(second.getRuleCode()).thenReturn(
                RuleV1ContractRegistry.RECENT_SECURITY_CHANGE_HIGH_AMOUNT);
        when(second.getObservationSummary()).thenReturn(JsonNodeFactory.instance.objectNode()
                .put("passwordEventId", password.toString())
                .put("limitEventId", limit.toString()));
        bind(List.of(second));
        when(transaction.getExternalCustomerRef()).thenReturn("customer");
        when(transaction.getSenderAccountRef()).thenReturn("sender");
        var first = service.correlation(transaction).orElseThrow();
        assertThat(first.scenarioCode()).isEqualTo("SCN002");
        when(transaction.getSenderAccountRef()).thenReturn("other-sender");
        var other = service.correlation(transaction).orElseThrow();
        assertThat(other.key()).isNotEqualTo(first.key());
    }
}
