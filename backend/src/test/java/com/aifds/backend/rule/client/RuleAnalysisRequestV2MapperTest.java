package com.aifds.backend.rule.client;

import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.rule.client.config.RuleAnalysisClientConfiguration;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleTransactionSnapshotRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleAnalysisRequestV2MapperTest {

    private final RuleAnalysisRequestV2Mapper mapper =
            new RuleAnalysisRequestV2Mapper();
    private RuleAnalysisRequest request;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new RuleAnalysisClientConfiguration()
                .ruleAnalysisObjectMapper(new Jackson2ObjectMapperBuilder());
        request = RuleAnalysisClientTestFixtures.request(objectMapper);
    }

    @Test
    void mapsMatchesIntoTheExplicitCanonicalTupleOrderWithoutMutatingSource() {
        List<ExternalRiskMatch> source = new ArrayList<>(List.of(
                match(
                        ExternalRiskSubjectType.DEVICE,
                        ExternalRiskType.RISK_DEVICE,
                        ExternalRiskReasonCode.RISK_DEVICE
                ),
                match(
                        ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT
                ),
                match(
                        ExternalRiskSubjectType.SENDER_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
                )
        ));
        ExternalRiskSnapshot snapshot =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot(source);

        RuleAnalysisRequestV2 actual = mapper.map(request, snapshot);

        assertThat(actual.externalRisk().matches())
                .extracting(match -> match.subjectType())
                .containsExactly(
                        ExternalRiskSubjectType.SENDER_ACCOUNT,
                        ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskSubjectType.DEVICE
                );
        assertThat(source)
                .extracting(ExternalRiskMatch::subjectType)
                .containsExactly(
                        ExternalRiskSubjectType.DEVICE,
                        ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskSubjectType.SENDER_ACCOUNT
                );
        assertThat(snapshot.matches())
                .extracting(ExternalRiskMatch::subjectType)
                .containsExactly(
                        ExternalRiskSubjectType.DEVICE,
                        ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskSubjectType.SENDER_ACCOUNT
                );
        assertThat(snapshot.matches()).isUnmodifiable();
    }

    @Test
    void mapsOneCanonicalMatch() {
        ExternalRiskSnapshot snapshot =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot(List.of(
                        match(
                                ExternalRiskSubjectType.SENDER_ACCOUNT,
                                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                                ExternalRiskReasonCode
                                        .SUSPICIOUS_SENDER_ACCOUNT
                        )
                ));

        RuleAnalysisRequestV2 actual = mapper.map(request, snapshot);

        assertThat(actual.externalRisk().matches())
                .extracting(match -> match.subjectType())
                .containsExactly(ExternalRiskSubjectType.SENDER_ACCOUNT);
    }

    @Test
    void mapsTwoMatchesInTheExplicitCanonicalOrder() {
        ExternalRiskSnapshot snapshot =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot(List.of(
                        match(
                                ExternalRiskSubjectType.DEVICE,
                                ExternalRiskType.RISK_DEVICE,
                                ExternalRiskReasonCode.RISK_DEVICE
                        ),
                        match(
                                ExternalRiskSubjectType.SENDER_ACCOUNT,
                                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                                ExternalRiskReasonCode
                                        .SUSPICIOUS_SENDER_ACCOUNT
                        )
                ));

        RuleAnalysisRequestV2 actual = mapper.map(request, snapshot);

        assertThat(actual.externalRisk().matches())
                .extracting(match -> match.subjectType())
                .containsExactly(
                        ExternalRiskSubjectType.SENDER_ACCOUNT,
                        ExternalRiskSubjectType.DEVICE
                );
    }

    @Test
    void rejectsRequestWhoseCutoffDoesNotMatchTransactionOccurredAt() {
        RuleAnalysisRequest invalidRequest = mock(RuleAnalysisRequest.class);
        RuleTransactionSnapshotRequest transaction =
                mock(RuleTransactionSnapshotRequest.class);
        when(invalidRequest.evaluationCutoffAt())
                .thenReturn(request.evaluationCutoffAt());
        when(invalidRequest.transaction()).thenReturn(transaction);
        when(transaction.occurredAt())
                .thenReturn(request.evaluationCutoffAt().minusSeconds(1));

        assertThatThrownBy(() -> mapper.map(
                invalidRequest,
                RuleAnalysisClientTestFixtures.externalRiskSnapshot()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "request evaluationCutoffAt must match transaction occurredAt"
                );
    }

    @Test
    void rejectsSnapshotWhoseEvaluationCutoffDoesNotMatchRequest() {
        ExternalRiskSnapshot valid =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot();
        ExternalRiskSnapshot mismatched = new ExternalRiskSnapshot(
                valid.transactionId(),
                valid.evaluationCutoffAt().plusSeconds(1),
                valid.lookedUpAt().plusSeconds(2),
                valid.providerCode(),
                valid.providerAsOf(),
                valid.lookupStatus(),
                valid.policyResult(),
                valid.matches()
        );

        assertThatThrownBy(() -> mapper.map(request, mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("snapshot evaluationCutoffAt must match request");
    }

    @Test
    void rejectsSnapshotForAnotherTransaction() {
        ExternalRiskSnapshot valid =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot();
        ExternalRiskSnapshot mismatched = new ExternalRiskSnapshot(
                UUID.fromString("10000000-0000-4000-8000-000000000002"),
                valid.evaluationCutoffAt(),
                valid.lookedUpAt(),
                valid.providerCode(),
                valid.providerAsOf(),
                valid.lookupStatus(),
                valid.policyResult(),
                valid.matches()
        );

        assertThatThrownBy(() -> mapper.map(request, mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("snapshot transactionId must match request");
    }

    @Test
    void rejectsSnapshotWhoseProviderStateIsAfterTheEvaluationCutoff() {
        ExternalRiskSnapshot valid =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot();
        ExternalRiskSnapshot futureProviderState = new ExternalRiskSnapshot(
                valid.transactionId(),
                valid.evaluationCutoffAt(),
                valid.lookedUpAt().plusSeconds(2),
                valid.providerCode(),
                valid.evaluationCutoffAt().plusSeconds(1),
                valid.lookupStatus(),
                valid.policyResult(),
                valid.matches()
        );

        assertThatThrownBy(() -> mapper.map(request, futureProviderState))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerAsOf must not be after evaluationCutoffAt");
    }

    @Test
    void rejectsSnapshotLookedUpBeforeTheEvaluationCutoff() {
        ExternalRiskSnapshot valid =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot();
        Instant beforeCutoff = valid.evaluationCutoffAt().minusSeconds(1);
        ExternalRiskSnapshot earlyLookup = new ExternalRiskSnapshot(
                valid.transactionId(),
                valid.evaluationCutoffAt(),
                beforeCutoff,
                valid.providerCode(),
                beforeCutoff,
                valid.lookupStatus(),
                valid.policyResult(),
                valid.matches()
        );

        assertThatThrownBy(() -> mapper.map(request, earlyLookup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lookedUpAt must not be before evaluationCutoffAt");
    }

    @Test
    void rejectsDuplicateMatchesBeforeCanonicalSorting() {
        ExternalRiskMatch sender = mock(ExternalRiskMatch.class);
        when(sender.subjectType())
                .thenReturn(ExternalRiskSubjectType.SENDER_ACCOUNT);
        when(sender.riskType())
                .thenReturn(ExternalRiskType.SUSPICIOUS_ACCOUNT);
        when(sender.reasonCode())
                .thenReturn(ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT);
        ExternalRiskSnapshot invalid = mockedSnapshot(
                ExternalRiskPolicyResult.MATCHED,
                List.of(sender, sender)
        );

        assertThatThrownBy(() -> mapper.map(request, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("matches must not contain duplicates");
        verify(sender, times(2)).subjectType();
        verify(sender, times(2)).riskType();
        verify(sender, times(2)).reasonCode();
    }

    @Test
    void rejectsMoreThanThreeMatchesBeforeInspectingDuplicates() {
        ExternalRiskMatch sender = match(
                ExternalRiskSubjectType.SENDER_ACCOUNT,
                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
        );
        ExternalRiskSnapshot invalid = mockedSnapshot(
                ExternalRiskPolicyResult.MATCHED,
                List.of(sender, sender, sender, sender)
        );

        assertThatThrownBy(() -> mapper.map(request, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("matches must contain at most 3 items");
    }

    @Test
    void rejectsUnsupportedMatchCombinations() {
        ExternalRiskSnapshot invalid = mockedSnapshot(
                ExternalRiskPolicyResult.MATCHED,
                List.of(match(
                        ExternalRiskSubjectType.SENDER_ACCOUNT,
                        ExternalRiskType.RISK_DEVICE,
                        ExternalRiskReasonCode.RISK_DEVICE
                ))
        );

        assertThatThrownBy(() -> mapper.map(request, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("match combination is unsupported");
    }

    @Test
    void mapsAnUnmatchedSnapshotWithoutInventingMatches() {
        ExternalRiskSnapshot unmatched =
                RuleAnalysisClientTestFixtures.externalRiskSnapshot(List.of());

        RuleAnalysisRequestV2 actual = mapper.map(request, unmatched);

        assertThat(actual.externalRisk().policyResult())
                .isEqualTo(ExternalRiskPolicyResult.UNMATCHED);
        assertThat(actual.externalRisk().matches()).isEmpty();
    }

    private ExternalRiskSnapshot mockedSnapshot(
            ExternalRiskPolicyResult policyResult,
            List<ExternalRiskMatch> matches
    ) {
        ExternalRiskSnapshot snapshot = mock(ExternalRiskSnapshot.class);
        when(snapshot.transactionId()).thenReturn(request.transaction().transactionId());
        when(snapshot.evaluationCutoffAt())
                .thenReturn(request.evaluationCutoffAt());
        when(snapshot.providerAsOf())
                .thenReturn(request.evaluationCutoffAt().minusSeconds(1));
        when(snapshot.lookedUpAt())
                .thenReturn(request.evaluationCutoffAt().plusSeconds(1));
        when(snapshot.providerCode()).thenReturn("EXTERNAL_RISK_MOCK_V1");
        when(snapshot.lookupStatus())
                .thenReturn(ExternalRiskLookupStatus.SUCCEEDED);
        when(snapshot.policyResult()).thenReturn(policyResult);
        when(snapshot.matches()).thenReturn(matches);
        return snapshot;
    }

    private ExternalRiskMatch match(
            ExternalRiskSubjectType subjectType,
            ExternalRiskType riskType,
            ExternalRiskReasonCode reasonCode
    ) {
        return new ExternalRiskMatch(subjectType, riskType, reasonCode);
    }
}
