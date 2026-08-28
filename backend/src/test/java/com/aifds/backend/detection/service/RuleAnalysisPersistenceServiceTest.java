package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.rule.client.RuleAnalysisRequestV2Mapper;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleTransactionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleTransactionType;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleVersionStatus;
import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleAnalysisPersistenceServiceTest {

    private static final Instant CUTOFF =
            Instant.parse("2026-08-13T01:00:00Z");
    private static final Instant STARTED_AT = CUTOFF.plusSeconds(1);

    @Mock
    private FinancialTransactionRepository transactionRepository;
    @Mock
    private DetectionResultRepository resultRepository;
    @Mock
    private DetectionEvidenceRepository evidenceRepository;
    @Mock
    private RuleVersionRepository ruleVersionRepository;
    @Mock
    private RuleAnalysisSnapshotAssembler snapshotAssembler;
    @Mock
    private RuleAnalysisRequestV2Mapper requestV2Mapper;
    @Mock
    private TransactionProcessingMetricsRecorder metricsRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuleAnalysisPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new RuleAnalysisPersistenceService(
                transactionRepository,
                resultRepository,
                evidenceRepository,
                ruleVersionRepository,
                snapshotAssembler,
                requestV2Mapper,
                metricsRecorder
        );
    }

    @Test
    void startsAnalysisAfterLockingAndReturnsImmutableAttempt() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction transaction = storedTransaction(transactionId);
        when(transactionRepository.findByTransactionIdForUpdate(transactionId))
                .thenReturn(Optional.of(transaction));
        when(resultRepository.findMaximumVersionByTransactionPk(41L))
                .thenReturn(2);
        when(resultRepository.saveAndFlush(any(DetectionResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                snapshot(transactionId);
        when(snapshotAssembler.assemble(transaction)).thenReturn(snapshot);

        StartedRuleAnalysisExecution execution = service.startAnalysis(
                transactionId,
                "score-v1",
                "feature-v1",
                null,
                "trace_analysis_start_01",
                STARTED_AT
        );
        StartedRuleAnalysis started = execution.startedAnalysis();

        ArgumentCaptor<DetectionResult> resultCaptor =
                ArgumentCaptor.forClass(DetectionResult.class);
        InOrder order = inOrder(
                transactionRepository,
                snapshotAssembler,
                resultRepository
        );
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transactionId);
        order.verify(snapshotAssembler).assemble(transaction);
        order.verify(resultRepository)
                .findMaximumVersionByTransactionPk(41L);
        order.verify(resultRepository).saveAndFlush(resultCaptor.capture());
        order.verify(transactionRepository).saveAndFlush(transaction);

        DetectionResult result = resultCaptor.getValue();
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.IN_PROGRESS);
        assertThat(started.transactionId()).isEqualTo(transactionId);
        assertThat(started.detectionResultId())
                .isEqualTo(result.getDetectionResultId());
        assertThat(started.detectionResultVersion()).isEqualTo(3);
        assertThat(started.ruleSetVersion()).isEqualTo(
                snapshot.ruleSetVersion()
        );
        assertThat(started.modelVersion()).isNull();
        assertThat(started.evaluationCutoffAt()).isEqualTo(CUTOFF);
        assertThat(started.analysisTraceId())
                .isEqualTo("trace_analysis_start_01");
        assertThat(execution.request()).isSameAs(snapshot.request());
        verifyNoInteractions(metricsRecorder);
        verify(transaction).startAnalysis();
    }

    @Test
    void startsV2OnlyAfterMappingAndReturnsTheExactImmutableRequest() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction transaction = storedTransaction(transactionId);
        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                snapshot(transactionId);
        ExternalRiskSnapshot externalRisk = externalRiskSnapshot(transactionId);
        RuleAnalysisRequestV2 requestV2 = new RuleAnalysisRequestV2Mapper()
                .map(snapshot.request(), externalRisk);
        when(transactionRepository.findByTransactionIdForUpdate(transactionId))
                .thenReturn(Optional.of(transaction));
        when(snapshotAssembler.assemble(transaction)).thenReturn(snapshot);
        when(requestV2Mapper.map(snapshot.request(), externalRisk))
                .thenReturn(requestV2);
        when(resultRepository.findMaximumVersionByTransactionPk(41L))
                .thenReturn(2);
        when(resultRepository.saveAndFlush(any(DetectionResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StartedRuleAnalysisV2Execution execution = service.startAnalysisV2(
                transactionId,
                externalRisk,
                "score-v1",
                "feature-v1",
                null,
                "trace_analysis_start_v2_01",
                STARTED_AT
        );

        InOrder order = inOrder(
                transactionRepository,
                snapshotAssembler,
                requestV2Mapper,
                resultRepository
        );
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transactionId);
        order.verify(snapshotAssembler).assemble(transaction);
        order.verify(requestV2Mapper).map(snapshot.request(), externalRisk);
        order.verify(resultRepository)
                .findMaximumVersionByTransactionPk(41L);
        order.verify(resultRepository).saveAndFlush(any(DetectionResult.class));
        order.verify(transactionRepository).saveAndFlush(transaction);
        assertThat(execution.request()).isSameAs(requestV2);
        assertThat(execution.startedAnalysis().transactionId())
                .isEqualTo(transactionId);
        assertThat(execution.startedAnalysis().detectionResultVersion())
                .isEqualTo(3);
        assertThat(execution.startedAnalysis().ruleSetVersion())
                .isEqualTo(snapshot.ruleSetVersion());
        verify(transaction).startAnalysis();
        verifyNoInteractions(evidenceRepository);
    }

    @Test
    void v2MapperFailureHappensBeforeVersionLookupOrAnyWrite() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction transaction = org.mockito.Mockito.mock(
                FinancialTransaction.class
        );
        when(transaction.getProcessingStatus())
                .thenReturn(TransactionProcessingStatus.RECEIVED);
        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                snapshot(transactionId);
        ExternalRiskSnapshot externalRisk = externalRiskSnapshot(transactionId);
        RuntimeException original = new IllegalArgumentException(
                "snapshot cutoff mismatch"
        );
        when(transactionRepository.findByTransactionIdForUpdate(transactionId))
                .thenReturn(Optional.of(transaction));
        when(snapshotAssembler.assemble(transaction)).thenReturn(snapshot);
        when(requestV2Mapper.map(snapshot.request(), externalRisk))
                .thenThrow(original);

        assertThatThrownBy(() -> service.startAnalysisV2(
                transactionId,
                externalRisk,
                "score-v1",
                "feature-v1",
                null,
                "trace_analysis_start_v2_failure",
                STARTED_AT
        )).isSameAs(original);

        InOrder order = inOrder(
                transactionRepository,
                snapshotAssembler,
                requestV2Mapper
        );
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transactionId);
        order.verify(snapshotAssembler).assemble(transaction);
        order.verify(requestV2Mapper).map(snapshot.request(), externalRisk);
        verify(resultRepository, never())
                .findMaximumVersionByTransactionPk(anyLong());
        verify(resultRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(transaction, never()).startAnalysis();
        verifyNoInteractions(evidenceRepository);
    }

    @Test
    void rejectsInvalidTransactionStateBeforeAllocatingVersion() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        transaction.startAnalysis();
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> service.startAnalysis(
                transaction.getTransactionId(),
                "score-v1",
                "feature-v1",
                null,
                "trace_analysis_start_02",
                STARTED_AT
        )).isInstanceOf(IllegalStateException.class);

        verify(resultRepository, never())
                .findMaximumVersionByTransactionPk(anyLong());
        verify(resultRepository, never()).saveAndFlush(any());
        verify(snapshotAssembler, never()).assemble(any());
    }

    @Test
    void completesEvidenceThenResultThenAdoptsTransaction() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(transaction);
        StartedRuleAnalysis started = started(result);
        RuleVersion ruleVersion = publishedAmountVersion();
        RuleEvidenceDraft draft = validDraft(ruleVersion, CUTOFF);
        transaction.startAnalysis();
        stubLockedAttempt(transaction, result);
        when(ruleVersionRepository.findByRuleVersionId(
                ruleVersion.getRuleVersionId()
        )).thenReturn(Optional.of(ruleVersion));

        service.completeAndAdopt(
                started,
                55,
                RiskLevel.HIGH,
                STARTED_AT.plusSeconds(1),
                List.of(draft)
        );

        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.COMPLETED);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(transaction.getAdoptedDetectionResult()).isSameAs(result);
        assertThat(transaction.getRiskLevel()).isEqualTo(RiskLevel.HIGH);

        InOrder order = inOrder(
                transactionRepository,
                resultRepository,
                evidenceRepository
        );
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transaction.getTransactionId());
        order.verify(resultRepository)
                .findByDetectionResultIdForUpdate(result.getDetectionResultId());
        order.verify(evidenceRepository).saveAllAndFlush(any());
        order.verify(resultRepository).saveAndFlush(result);
        order.verify(transactionRepository).saveAndFlush(transaction);
    }

    @Test
    void allowsEmptyEvidenceForValidatedUnmatchedResult() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(transaction);
        transaction.startAnalysis();
        stubLockedAttempt(transaction, result);

        service.completeAndAdopt(
                started(result),
                0,
                RiskLevel.LOW,
                STARTED_AT.plusSeconds(1),
                List.of()
        );

        verify(evidenceRepository).saveAllAndFlush(List.of());
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(result.getRiskScore()).isZero();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @ParameterizedTest
    @EnumSource(AttemptMismatch.class)
    void rejectsEveryStartedAttemptMismatchBeforeCompletingOrAdopting(
            AttemptMismatch mismatch
    ) {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(
                transaction,
                mismatch.storedModelVersion()
        );
        transaction.startAnalysis();
        StartedRuleAnalysis mismatched = mismatchedStarted(result, mismatch);
        stubLockedAttempt(mismatched, transaction, result);

        assertThatThrownBy(() -> service.completeAndAdopt(
                mismatched,
                10,
                RiskLevel.LOW,
                STARTED_AT.plusSeconds(1),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(evidenceRepository, never()).saveAllAndFlush(any());
        verify(resultRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.IN_PROGRESS);
        assertThat(transaction.getAdoptedDetectionResult()).isNull();
        assertThat(transaction.getRiskLevel()).isNull();
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZING);
    }

    @Test
    void rejectsResultThatBelongsToAnotherTransaction() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        FinancialTransaction other = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(other);
        transaction.startAnalysis();
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        when(resultRepository.findByDetectionResultIdForUpdate(
                result.getDetectionResultId()
        )).thenReturn(Optional.of(result));
        StartedRuleAnalysis started = new StartedRuleAnalysis(
                transaction.getTransactionId(),
                result.getDetectionResultId(),
                result.getDetectionResultVersion(),
                result.getRuleSetVersion(),
                result.getScoringPolicyVersion(),
                result.getFeatureVersion(),
                result.getModelVersion(),
                result.getEvaluationCutoffAt(),
                result.getAnalysisTraceId()
        );

        assertThatThrownBy(() -> service.completeAndAdopt(
                started,
                10,
                RiskLevel.LOW,
                STARTED_AT.plusSeconds(1),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked transaction");

        verify(evidenceRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void rejectsResultThatIsNotInProgress() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult pending = pendingResult(transaction);
        transaction.startAnalysis();
        stubLockedAttempt(transaction, pending);

        assertThatThrownBy(() -> service.completeAndAdopt(
                started(pending),
                10,
                RiskLevel.LOW,
                STARTED_AT.plusSeconds(1),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IN_PROGRESS");

        verify(evidenceRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void doesNotCompleteOrAdoptWhenEvidenceAssemblyFails() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(transaction);
        RuleVersion ruleVersion = publishedAmountVersion();
        RuleEvidenceDraft invalidDraft = validDraft(
                ruleVersion,
                CUTOFF.plusSeconds(1)
        );
        transaction.startAnalysis();
        stubLockedAttempt(transaction, result);
        when(ruleVersionRepository.findByRuleVersionId(
                ruleVersion.getRuleVersionId()
        )).thenReturn(Optional.of(ruleVersion));

        assertThatThrownBy(() -> service.completeAndAdopt(
                started(result),
                55,
                RiskLevel.HIGH,
                STARTED_AT.plusSeconds(1),
                List.of(invalidDraft)
        )).isInstanceOf(IllegalStateException.class);

        verify(evidenceRepository, never()).saveAllAndFlush(any());
        verify(resultRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.IN_PROGRESS);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZING);
    }

    @Test
    void failsResultAndTransactionUsingTransactionFirstLockOrder() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(transaction);
        transaction.startAnalysis();
        stubLockedAttempt(transaction, result);

        service.failAnalysis(
                started(result),
                "DEPENDENCY_TIMEOUT",
                STARTED_AT.plusSeconds(1)
        );

        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.FAILED);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.FAILED);
        assertThat(transaction.getAdoptedDetectionResult()).isNull();

        InOrder order = inOrder(transactionRepository, resultRepository);
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transaction.getTransactionId());
        order.verify(resultRepository)
                .findByDetectionResultIdForUpdate(result.getDetectionResultId());
        order.verify(transactionRepository).saveAndFlush(transaction);
    }

    @ParameterizedTest
    @EnumSource(AttemptMismatch.class)
    void rejectsEveryStartedAttemptMismatchBeforeFailing(
            AttemptMismatch mismatch
    ) {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(
                transaction,
                mismatch.storedModelVersion()
        );
        transaction.startAnalysis();
        StartedRuleAnalysis mismatched = mismatchedStarted(result, mismatch);
        stubLockedAttempt(mismatched, transaction, result);

        assertThatThrownBy(() -> service.failAnalysis(
                mismatched,
                "DEPENDENCY_TIMEOUT",
                STARTED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(resultRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        assertThat(result.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.IN_PROGRESS);
        assertThat(transaction.getAdoptedDetectionResult()).isNull();
        assertThat(transaction.getRiskLevel()).isNull();
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZING);
    }

    @Test
    void rejectsLateSuccessAndFailureAfterTerminalCompletion() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        DetectionResult result = startedResult(transaction);
        StartedRuleAnalysis started = started(result);
        transaction.startAnalysis();
        result.complete(10, RiskLevel.LOW, STARTED_AT.plusSeconds(1));
        transaction.adoptDetectionResult(result);
        stubLockedAttempt(transaction, result);

        assertThatThrownBy(() -> service.completeAndAdopt(
                started,
                10,
                RiskLevel.LOW,
                STARTED_AT.plusSeconds(2),
                List.of()
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.failAnalysis(
                started,
                "LATE_FAILURE",
                STARTED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class);

        verify(evidenceRepository, never()).saveAllAndFlush(any());
        verify(resultRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void startedAttemptRejectsMissingOrBlankIdentity() {
        assertThatThrownBy(() -> new StartedRuleAnalysis(
                null,
                UUID.randomUUID(),
                1,
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_analysis_record"
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StartedRuleAnalysis(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_analysis_record"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartedRuleAnalysis(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                " ",
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_analysis_record"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startedV2ExecutionRejectsNullAndIdentityMismatches() {
        UUID transactionId = UUID.randomUUID();
        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                snapshot(transactionId);
        RuleAnalysisRequestV2 request = new RuleAnalysisRequestV2Mapper().map(
                snapshot.request(),
                externalRiskSnapshot(transactionId)
        );
        StartedRuleAnalysis validStarted = new StartedRuleAnalysis(
                transactionId,
                UUID.randomUUID(),
                1,
                snapshot.ruleSetVersion(),
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_analysis_v2_record"
        );

        assertThatThrownBy(() -> new StartedRuleAnalysisV2Execution(
                null,
                request
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StartedRuleAnalysisV2Execution(
                validStarted,
                null
        )).isInstanceOf(NullPointerException.class);

        RuleAnalysisRequestV2 transactionMismatch = org.mockito.Mockito.mock(
                RuleAnalysisRequestV2.class
        );
        RuleTransactionSnapshotRequest transaction = org.mockito.Mockito.mock(
                RuleTransactionSnapshotRequest.class
        );
        when(transactionMismatch.transaction()).thenReturn(transaction);
        when(transaction.transactionId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> new StartedRuleAnalysisV2Execution(
                validStarted,
                transactionMismatch
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction");

        RuleAnalysisRequestV2 cutoffMismatch = org.mockito.Mockito.mock(
                RuleAnalysisRequestV2.class
        );
        when(cutoffMismatch.transaction()).thenReturn(request.transaction());
        when(cutoffMismatch.evaluationCutoffAt())
                .thenReturn(CUTOFF.plusSeconds(1));
        assertThatThrownBy(() -> new StartedRuleAnalysisV2Execution(
                validStarted,
                cutoffMismatch
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cutoff");

        StartedRuleAnalysis ruleSetMismatch = new StartedRuleAnalysis(
                transactionId,
                UUID.randomUUID(),
                1,
                "b".repeat(64),
                "score-v1",
                "feature-v1",
                null,
                CUTOFF,
                "trace_analysis_v2_record"
        );
        assertThatThrownBy(() -> new StartedRuleAnalysisV2Execution(
                ruleSetMismatch,
                request
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleSetVersion");
    }

    private void stubLockedAttempt(
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        when(resultRepository.findByDetectionResultIdForUpdate(
                result.getDetectionResultId()
        )).thenReturn(Optional.of(result));
    }

    private void stubLockedAttempt(
            StartedRuleAnalysis started,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        when(transactionRepository.findByTransactionIdForUpdate(
                started.transactionId()
        )).thenReturn(Optional.of(transaction));
        when(resultRepository.findByDetectionResultIdForUpdate(
                started.detectionResultId()
        )).thenReturn(Optional.of(result));
    }

    private FinancialTransaction storedTransaction(UUID transactionId) {
        FinancialTransaction transaction = org.mockito.Mockito.mock(
                FinancialTransaction.class
        );
        when(transaction.getId()).thenReturn(41L);
        when(transaction.getTransactionId()).thenReturn(transactionId);
        when(transaction.getOccurredAt()).thenReturn(CUTOFF);
        when(transaction.getProcessingStatus())
                .thenReturn(TransactionProcessingStatus.RECEIVED);
        return transaction;
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                CUTOFF,
                "customer_ref",
                "sender_ref",
                "recipient_ref",
                TransactionChannel.MOBILE_BANKING,
                "device_ref"
        );
    }

    private DetectionResult pendingResult(FinancialTransaction transaction) {
        return pendingResult(transaction, null);
    }

    private DetectionResult pendingResult(
            FinancialTransaction transaction,
            String modelVersion
    ) {
        return DetectionResult.pending(
                transaction,
                1,
                "rule-set-v1",
                "score-v1",
                "feature-v1",
                modelVersion,
                CUTOFF,
                "trace_analysis_attempt"
        );
    }

    private DetectionResult startedResult(FinancialTransaction transaction) {
        return startedResult(transaction, null);
    }

    private DetectionResult startedResult(
            FinancialTransaction transaction,
            String modelVersion
    ) {
        DetectionResult result = pendingResult(transaction, modelVersion);
        result.start(STARTED_AT);
        return result;
    }

    private StartedRuleAnalysis mismatchedStarted(
            DetectionResult result,
            AttemptMismatch mismatch
    ) {
        FinancialTransaction transaction = result.getFinancialTransaction();
        return new StartedRuleAnalysis(
                mismatch == AttemptMismatch.TRANSACTION_ID
                        ? UUID.randomUUID()
                        : transaction.getTransactionId(),
                mismatch == AttemptMismatch.DETECTION_RESULT_ID
                        ? UUID.randomUUID()
                        : result.getDetectionResultId(),
                mismatch == AttemptMismatch.DETECTION_RESULT_VERSION
                        ? Math.addExact(result.getDetectionResultVersion(), 1)
                        : result.getDetectionResultVersion(),
                mismatch == AttemptMismatch.RULE_SET_VERSION
                        ? "rule-set-v2"
                        : result.getRuleSetVersion(),
                mismatch == AttemptMismatch.SCORING_POLICY_VERSION
                        ? "score-v2"
                        : result.getScoringPolicyVersion(),
                mismatch == AttemptMismatch.FEATURE_VERSION
                        ? "feature-v2"
                        : result.getFeatureVersion(),
                mismatch.startedModelVersion(result.getModelVersion()),
                mismatch == AttemptMismatch.EVALUATION_CUTOFF_AT
                        ? result.getEvaluationCutoffAt().plusNanos(1)
                        : result.getEvaluationCutoffAt(),
                mismatch == AttemptMismatch.ANALYSIS_TRACE_ID
                        ? "trace_analysis_attempt_mismatch"
                        : result.getAnalysisTraceId()
        );
    }

    private StartedRuleAnalysis started(DetectionResult result) {
        return new StartedRuleAnalysis(
                result.getFinancialTransaction().getTransactionId(),
                result.getDetectionResultId(),
                result.getDetectionResultVersion(),
                result.getRuleSetVersion(),
                result.getScoringPolicyVersion(),
                result.getFeatureVersion(),
                result.getModelVersion(),
                result.getEvaluationCutoffAt(),
                result.getAnalysisTraceId()
        );
    }

    private RuleVersion publishedAmountVersion() {
        FraudRule rule = FraudRule.create(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                "Rule v1 test rule",
                "Rule v1 persistence boundary test rule"
        );
        RuleVersion version = RuleVersion.draft(
                rule,
                1,
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition(),
                CUTOFF.minusSeconds(120),
                null
        );
        version.publish(CUTOFF.minusSeconds(180));
        return version;
    }

    private RuleEvidenceDraft validDraft(
            RuleVersion ruleVersion,
            Instant evidenceOccurredAt
    ) {
        return new RuleEvidenceDraft(
                ruleVersion.getRuleVersionId(),
                "Rule v1 detection evidence",
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000"),
                evidenceOccurredAt,
                0
        );
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }

    @Test
    void startUsesRequiresNewRepeatableReadAndPropagatesLockFailure()
            throws Exception {
        Method method = RuleAnalysisPersistenceService.class.getMethod(
                "startAnalysis",
                UUID.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class
        );
        Transactional transactional = method.getAnnotation(
                Transactional.class
        );
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.isolation())
                .isEqualTo(Isolation.REPEATABLE_READ);
        Method v2Method = RuleAnalysisPersistenceService.class.getMethod(
                "startAnalysisV2",
                UUID.class,
                ExternalRiskSnapshot.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class
        );
        Transactional v2Transactional = v2Method.getAnnotation(
                Transactional.class
        );
        assertThat(v2Transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(v2Transactional.isolation())
                .isEqualTo(Isolation.REPEATABLE_READ);

        UUID transactionId = UUID.randomUUID();
        CannotAcquireLockException failure =
                new CannotAcquireLockException("serialization");
        when(transactionRepository.findByTransactionIdForUpdate(transactionId))
                .thenThrow(failure);
        assertThatThrownBy(() -> service.startAnalysis(
                transactionId,
                "score-v1",
                "feature-v1",
                null,
                "trace_analysis_concurrent",
                STARTED_AT
        )).isSameAs(failure);
        verify(snapshotAssembler, never()).assemble(any());
        verify(resultRepository, never())
                .findMaximumVersionByTransactionPk(anyLong());
        verify(resultRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    private RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot
    snapshot(UUID transactionId) {
        RuleVersionSnapshotRequest rule = new RuleVersionSnapshotRequest(
                UUID.fromString("10000000-0000-4000-8000-000000000004"),
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                RuleLifecycleStatus.ACTIVE,
                UUID.fromString("20000000-0000-4000-8000-000000000004"),
                1,
                RuleVersionStatus.PUBLISHED,
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                10,
                objectMapper.createObjectNode()
                        .put("eventType", "BENEFICIARY_REGISTERED")
                        .put("windowSeconds", 86_400)
                        .put(
                                "matchPolicy",
                                "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY"
                        )
                        .put(
                                "selectionPolicy",
                                "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                        ),
                CUTOFF.minusSeconds(120),
                null
        );
        RuleAnalysisRequest request = new RuleAnalysisRequest(
                CUTOFF,
                new RuleTransactionSnapshotRequest(
                        transactionId,
                        RuleTransactionType.ACCOUNT_TRANSFER,
                        "10000000",
                        "KRW",
                        CUTOFF,
                        "customer_ref",
                        "sender_ref",
                        "recipient_ref",
                        "device_ref"
                ),
                List.of(),
                List.of(rule)
        );
        String ruleSetVersion = new CanonicalRuleSetVersionCalculator()
                .calculate(List.of(
                        new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                                rule.fraudRuleId(),
                                rule.ruleVersionId(),
                                rule.ruleCode(),
                                rule.versionNumber()
                        )
                ));
        return new RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot(
                ruleSetVersion,
                request
        );
    }

    private ExternalRiskSnapshot externalRiskSnapshot(UUID transactionId) {
        return new ExternalRiskSnapshot(
                transactionId,
                CUTOFF,
                CUTOFF.plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF.minusSeconds(1),
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        );
    }

    private enum AttemptMismatch {
        TRANSACTION_ID,
        DETECTION_RESULT_ID,
        DETECTION_RESULT_VERSION,
        RULE_SET_VERSION,
        SCORING_POLICY_VERSION,
        FEATURE_VERSION,
        MODEL_VERSION_NULL_TO_VALUE,
        MODEL_VERSION_VALUE_TO_NULL,
        EVALUATION_CUTOFF_AT,
        ANALYSIS_TRACE_ID;

        private String storedModelVersion() {
            return this == MODEL_VERSION_VALUE_TO_NULL ? "model-v1" : null;
        }

        private String startedModelVersion(String storedModelVersion) {
            return switch (this) {
                case MODEL_VERSION_NULL_TO_VALUE -> "model-v1";
                case MODEL_VERSION_VALUE_TO_NULL -> null;
                default -> storedModelVersion;
            };
        }
    }
}
