package com.aifds.backend.idempotency.service;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyRecoveryServiceTest {

    private static final Instant DATABASE_NOW =
            Instant.parse("2026-08-28T04:30:00.123456Z");

    @Mock
    private IdempotencyRecordRepository recordRepository;
    @Mock
    private DatabaseTransactionTimestampProvider timestampProvider;
    @Mock
    private IdempotencyRecoveryTransaction recoveryTransaction;
    @Mock
    private IdempotencyRecoveryAuditWriter auditWriter;

    private IdempotencyRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyRecoveryService(
                recordRepository,
                timestampProvider,
                recoveryTransaction,
                auditWriter
        );
    }

    @Test
    void usesApprovedDefaultsAndOneDatabaseTimestamp() {
        IdempotencyRecoveryCandidate candidate =
                new IdempotencyRecoveryCandidate(
                        11L,
                        UUID.randomUUID(),
                        DATABASE_NOW.minus(Duration.ofHours(1))
                );
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        when(recordRepository.findRecoveryCandidates(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                IdempotencyProcessingStatus.IN_PROGRESS,
                DATABASE_NOW.minus(Duration.ofMinutes(30)),
                Limit.of(50)
        )).thenReturn(List.of(candidate));

        assertThat(service.findLongRunningCandidates())
                .containsExactly(candidate);
        verify(timestampProvider).currentTransactionTimestamp();
    }

    @ParameterizedTest
    @MethodSource("approvedThresholds")
    void acceptsThresholdBoundaries(Duration threshold) {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        when(recordRepository.findRecoveryCandidates(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                IdempotencyProcessingStatus.IN_PROGRESS,
                DATABASE_NOW.minus(threshold),
                Limit.of(50)
        )).thenReturn(List.of());

        assertThat(service.findLongRunningCandidates(threshold, 50)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("rejectedThresholds")
    void rejectsThresholdOutsideApprovedRange(Duration threshold) {
        assertThatThrownBy(() ->
                service.findLongRunningCandidates(threshold, 50)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        verify(timestampProvider, never()).currentTransactionTimestamp();
    }

    @ParameterizedTest
    @MethodSource("approvedPageSizes")
    void acceptsPageSizeBoundaries(int pageSize) {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        when(recordRepository.findRecoveryCandidates(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                IdempotencyProcessingStatus.IN_PROGRESS,
                DATABASE_NOW.minus(Duration.ofMinutes(30)),
                Limit.of(pageSize)
        )).thenReturn(List.of());

        assertThat(service.findLongRunningCandidates(
                Duration.ofMinutes(30),
                pageSize
        )).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("rejectedPageSizes")
    void rejectsPageSizeOutsideApprovedRange(int pageSize) {
        assertThatThrownBy(() -> service.findLongRunningCandidates(
                Duration.ofMinutes(30),
                pageSize
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
        verify(timestampProvider, never()).currentTransactionTimestamp();
    }

    @Test
    void candidateExposesOnlyApprovedNonSensitiveFields() {
        assertThat(Arrays.stream(
                IdempotencyRecoveryCandidate.class.getRecordComponents()
        ).map(RecordComponent::getName)).containsExactly(
                "idempotencyRecordId",
                "transactionId",
                "updatedAt"
        ).doesNotContain(
                "idempotencyKey",
                "requestFingerprint",
                "responseSnapshot",
                "expiresAt"
        );
    }

    @Test
    void exposesEveryApprovedTypedDecisionExactly() {
        assertThat(IdempotencyRecoveryDecision.values())
                .extracting(Enum::name)
                .containsExactly(
                        "RECOVERABLE_COMPLETION_GAP",
                        "MISSING_IDEMPOTENCY_RECORD",
                        "MISSING_TRANSACTION",
                        "PROCESSING_INDETERMINATE",
                        "FINALIZATION_INCOMPLETE",
                        "CONFIRMED_DOMAIN_FAILURE",
                        "INCONSISTENT_FINAL_STATE",
                        "INCONSISTENT_CASE_RELATIONSHIP",
                        "FINALIZATION_AUDIT_MISMATCH",
                        "CONFLICTING_IDEMPOTENCY_DATA",
                        "ALREADY_TERMINAL",
                        "INTERNAL_FAILURE"
                );
    }

    @Test
    void delegatesValidRecoveryWithoutWritingFailureAudit() {
        long recordId = 23L;
        IdempotencyRecoveryResult expected =
                IdempotencyRecoveryResult.rejected(
                        recordId,
                        null,
                        IdempotencyRecoveryDecision.MISSING_TRANSACTION
                );
        when(recoveryTransaction.recover(
                recordId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).thenReturn(expected);

        assertThat(service.recover(
                recordId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).isSameAs(expected);
        verify(auditWriter, never()).writeInternalFailure(
                recordId,
                null,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
    }

    @Test
    void preservesOriginalFailureAndSuppressesAuditWriterFailure() {
        long recordId = 24L;
        IllegalStateException original = new IllegalStateException(
                "sensitive original detail"
        );
        IllegalStateException auditFailure = new IllegalStateException(
                "audit persistence failed"
        );
        when(recoveryTransaction.recover(
                recordId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).thenThrow(original);
        doThrow(auditFailure).when(auditWriter).writeInternalFailure(
                recordId,
                null,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );

        assertThatThrownBy(() -> service.recover(
                recordId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(auditFailure);
    }

    @Test
    void rejectsInvalidActorBeforeStartingRecovery() {
        assertThatThrownBy(() -> service.recover(
                25L,
                AuditActorType.USER,
                "analyst@example.com"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID v4");
        verify(recoveryTransaction, never()).recover(
                25L,
                AuditActorType.USER,
                "analyst@example.com"
        );
    }

    @Test
    void classifiesTerminalAndConflictingIdempotencyDataFailClosed() {
        IdempotencyRecoveryTransaction transaction = classifier();
        IdempotencyRecord record = mock(IdempotencyRecord.class);
        when(record.getProcessingStatus())
                .thenReturn(IdempotencyProcessingStatus.COMPLETED);

        assertThat(classifyIdempotency(transaction, record))
                .isEqualTo(IdempotencyRecoveryDecision.ALREADY_TERMINAL);

        when(record.getProcessingStatus())
                .thenReturn(IdempotencyProcessingStatus.IN_PROGRESS);
        when(record.getOperationScope()).thenReturn("POST:/api/v1/other");
        assertThat(classifyIdempotency(transaction, record)).isEqualTo(
                IdempotencyRecoveryDecision.CONFLICTING_IDEMPOTENCY_DATA
        );

        when(record.getOperationScope()).thenReturn(
                IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE
        );
        when(record.getFinishedAt()).thenReturn(DATABASE_NOW);
        assertThat(classifyIdempotency(transaction, record)).isEqualTo(
                IdempotencyRecoveryDecision.CONFLICTING_IDEMPOTENCY_DATA
        );
    }

    @ParameterizedTest
    @MethodSource("transactionStateDecisions")
    void classifiesEveryNonFinalTransactionState(
            TransactionProcessingStatus status,
            IdempotencyRecoveryDecision expected
    ) {
        FinancialTransaction financialTransaction =
                mock(FinancialTransaction.class);
        when(financialTransaction.getProcessingStatus()).thenReturn(status);

        assertThat(ReflectionTestUtils
                .<IdempotencyRecoveryDecision>invokeMethod(
                        classifier(),
                        "classifyTransaction",
                        financialTransaction
                )).isEqualTo(expected);
    }

    @Test
    void classifiesDetectionStatesOwnershipAndRiskFailClosed() {
        IdempotencyRecoveryTransaction transaction = classifier();
        FinancialTransaction financialTransaction =
                approvedTransaction(RiskLevel.LOW);

        assertThat(classifyDetection(
                transaction,
                financialTransaction,
                null
        )).isEqualTo(IdempotencyRecoveryDecision.INCONSISTENT_FINAL_STATE);

        for (DetectionAnalysisStatus status : List.of(
                DetectionAnalysisStatus.PENDING,
                DetectionAnalysisStatus.IN_PROGRESS
        )) {
            DetectionResult result = mock(DetectionResult.class);
            when(result.getAnalysisStatus()).thenReturn(status);
            assertThat(classifyDetection(
                    transaction,
                    financialTransaction,
                    result
            )).isEqualTo(
                    IdempotencyRecoveryDecision.PROCESSING_INDETERMINATE
            );
        }

        DetectionResult failed = mock(DetectionResult.class);
        when(failed.getAnalysisStatus())
                .thenReturn(DetectionAnalysisStatus.FAILED);
        assertThat(classifyDetection(
                transaction,
                financialTransaction,
                failed
        )).isEqualTo(IdempotencyRecoveryDecision.CONFIRMED_DOMAIN_FAILURE);

        DetectionResult wrongOwner = completedResult(RiskLevel.LOW);
        when(wrongOwner.belongsTo(financialTransaction)).thenReturn(false);
        assertThat(classifyDetection(
                transaction,
                financialTransaction,
                wrongOwner
        )).isEqualTo(IdempotencyRecoveryDecision.INCONSISTENT_FINAL_STATE);

        DetectionResult wrongRisk = completedResult(RiskLevel.HIGH);
        when(wrongRisk.belongsTo(financialTransaction)).thenReturn(true);
        assertThat(classifyDetection(
                transaction,
                financialTransaction,
                wrongRisk
        )).isEqualTo(IdempotencyRecoveryDecision.INCONSISTENT_FINAL_STATE);
    }

    @ParameterizedTest
    @MethodSource("approvedFinalCombinations")
    void acceptsExactlyFourApprovedFinalCombinations(
            RiskLevel riskLevel,
            TransactionProcessingStatus status,
            RiskResponseOutcome outcome
    ) {
        FinancialTransaction transaction = mock(FinancialTransaction.class);
        lenient().when(transaction.getRiskLevel()).thenReturn(riskLevel);
        when(transaction.getProcessingStatus()).thenReturn(status);
        when(transaction.getRiskResponseOutcome()).thenReturn(outcome);

        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(
                classifier(),
                "validFinalCombination",
                transaction
        )).isTrue();
    }

    @Test
    void rejectsFinalCombinationMismatchAndEvidenceOwnershipMismatch() {
        FinancialTransaction transaction = approvedTransaction(RiskLevel.HIGH);
        when(transaction.getProcessingStatus())
                .thenReturn(TransactionProcessingStatus.APPROVED);
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(
                classifier(),
                "validFinalCombination",
                transaction
        )).isFalse();

        DetectionResult adopted = completedResult(RiskLevel.LOW);
        DetectionResult other = completedResult(RiskLevel.LOW);
        DetectionEvidence owned = mock(DetectionEvidence.class);
        DetectionEvidence foreign = mock(DetectionEvidence.class);
        when(owned.getDetectionResult()).thenReturn(adopted);
        when(foreign.getDetectionResult()).thenReturn(other);

        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(
                classifier(),
                "evidenceBelongsTo",
                List.of(owned),
                adopted
        )).isTrue();
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(
                classifier(),
                "evidenceBelongsTo",
                List.of(owned, foreign),
                adopted
        )).isFalse();
    }

    @Test
    void validatesCaseCardinalityAndOwnershipForEveryRiskBoundary() {
        IdempotencyRecoveryTransaction classifier = classifier();
        FinancialTransaction low = approvedTransaction(RiskLevel.LOW);
        FinancialTransaction high = approvedTransaction(RiskLevel.HIGH);
        FraudCase fraudCase = mock(FraudCase.class);
        CaseTransaction owned = mock(CaseTransaction.class);
        when(owned.getFraudCase()).thenReturn(fraudCase);
        when(owned.belongsTo(fraudCase, high)).thenReturn(true);

        assertThat(caseLinksValid(classifier, low, List.of())).isTrue();
        assertThat(caseLinksValid(classifier, low, List.of(owned))).isFalse();
        assertThat(caseLinksValid(classifier, high, List.of(owned))).isTrue();
        assertThat(caseLinksValid(
                classifier,
                high,
                List.of(owned, owned)
        )).isFalse();

        when(owned.belongsTo(fraudCase, high)).thenReturn(false);
        assertThat(caseLinksValid(classifier, high, List.of(owned))).isFalse();
    }

    @Test
    void validatesExactFinalizationAuditPairAndContent() {
        IdempotencyRecoveryTransaction classifier = classifier();
        FinancialTransaction transaction = approvedTransaction(RiskLevel.LOW);
        UUID transactionId = UUID.randomUUID();
        when(transaction.getTransactionId()).thenReturn(transactionId);
        DetectionResult result = completedResult(RiskLevel.LOW);
        UUID detectionId = result.getDetectionResultId();
        when(result.getDetectionResultVersion()).thenReturn(3);
        ObjectMapper mapper = new ObjectMapper();

        AuditLog riskAudit = audit(
                transactionId,
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                null,
                mapper.createObjectNode().put("riskResponseOutcome", "APPROVED"),
                mapper.createObjectNode()
                        .put("sourceRiskLevel", "LOW")
                        .put("detectionResultId", detectionId.toString())
                        .put("detectionResultVersion", 3)
        );
        AuditLog statusAudit = audit(
                transactionId,
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                mapper.createObjectNode().put("processingStatus", "ANALYZED"),
                mapper.createObjectNode().put("processingStatus", "APPROVED"),
                mapper.createObjectNode()
                        .put("sourceRiskLevel", "LOW")
                        .put("detectionResultId", detectionId.toString())
                        .put("detectionResultVersion", 3)
        );

        assertThat(finalizationLogsValid(
                classifier,
                List.of(riskAudit, statusAudit),
                transaction,
                result
        )).isTrue();
        assertThat(finalizationLogsValid(
                classifier,
                List.of(riskAudit),
                transaction,
                result
        )).isFalse();
        assertThat(finalizationLogsValid(
                classifier,
                List.of(riskAudit, riskAudit),
                transaction,
                result
        )).isFalse();
    }

    private IdempotencyRecoveryTransaction classifier() {
        return new IdempotencyRecoveryTransaction(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private IdempotencyRecoveryDecision classifyIdempotency(
            IdempotencyRecoveryTransaction transaction,
            IdempotencyRecord record
    ) {
        return ReflectionTestUtils.invokeMethod(
                transaction,
                "classifyIdempotency",
                record
        );
    }

    private IdempotencyRecoveryDecision classifyDetection(
            IdempotencyRecoveryTransaction transaction,
            FinancialTransaction financialTransaction,
            DetectionResult result
    ) {
        return ReflectionTestUtils.invokeMethod(
                transaction,
                "classifyDetection",
                financialTransaction,
                result
        );
    }

    private boolean caseLinksValid(
            IdempotencyRecoveryTransaction transaction,
            FinancialTransaction financialTransaction,
            List<CaseTransaction> links
    ) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                transaction,
                "caseLinksValid",
                financialTransaction,
                links
        ));
    }

    private boolean finalizationLogsValid(
            IdempotencyRecoveryTransaction classifier,
            List<AuditLog> logs,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                classifier,
                "validFinalizationLogs",
                logs,
                transaction,
                result
        ));
    }

    private FinancialTransaction approvedTransaction(RiskLevel riskLevel) {
        FinancialTransaction transaction = mock(FinancialTransaction.class);
        when(transaction.getRiskLevel()).thenReturn(riskLevel);
        switch (riskLevel) {
            case LOW -> {
                lenient().when(transaction.getProcessingStatus())
                        .thenReturn(TransactionProcessingStatus.APPROVED);
                lenient().when(transaction.getRiskResponseOutcome())
                        .thenReturn(RiskResponseOutcome.APPROVED);
            }
            case MEDIUM -> {
                lenient().when(transaction.getProcessingStatus())
                        .thenReturn(TransactionProcessingStatus.APPROVED);
                lenient().when(transaction.getRiskResponseOutcome()).thenReturn(
                        RiskResponseOutcome.APPROVED_WITH_MONITORING
                );
            }
            case HIGH -> {
                lenient().when(transaction.getProcessingStatus()).thenReturn(
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                );
                lenient().when(transaction.getRiskResponseOutcome()).thenReturn(
                        RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
                );
            }
            case CRITICAL -> {
                lenient().when(transaction.getProcessingStatus())
                        .thenReturn(TransactionProcessingStatus.HELD);
                lenient().when(transaction.getRiskResponseOutcome())
                        .thenReturn(RiskResponseOutcome.HELD);
            }
        }
        return transaction;
    }

    private DetectionResult completedResult(RiskLevel riskLevel) {
        DetectionResult result = mock(DetectionResult.class);
        lenient().when(result.getAnalysisStatus())
                .thenReturn(DetectionAnalysisStatus.COMPLETED);
        lenient().when(result.getRiskLevel()).thenReturn(riskLevel);
        lenient().when(result.getDetectionResultId())
                .thenReturn(UUID.randomUUID());
        return result;
    }

    private AuditLog audit(
            UUID transactionId,
            AuditAction action,
            AuditReasonCode reason,
            com.fasterxml.jackson.databind.JsonNode before,
            com.fasterxml.jackson.databind.JsonNode after,
            com.fasterxml.jackson.databind.JsonNode metadata
    ) {
        AuditLog audit = mock(AuditLog.class);
        when(audit.getAction()).thenReturn(action);
        when(audit.getReasonCode()).thenReturn(reason);
        when(audit.getTargetType())
                .thenReturn(AuditTargetType.FINANCIAL_TRANSACTION);
        when(audit.getTargetId()).thenReturn(transactionId);
        when(audit.getTransactionId()).thenReturn(transactionId);
        when(audit.getBeforeValueSummary()).thenReturn(before);
        when(audit.getAfterValueSummary()).thenReturn(after);
        when(audit.getMetadata()).thenReturn(metadata);
        return audit;
    }

    private static Stream<Duration> approvedThresholds() {
        return Stream.of(Duration.ofMinutes(5), Duration.ofDays(7));
    }

    private static Stream<Duration> rejectedThresholds() {
        return Stream.of(
                Duration.ofMinutes(5).minusNanos(1),
                Duration.ofDays(7).plusNanos(1)
        );
    }

    private static Stream<Integer> approvedPageSizes() {
        return Stream.of(1, 50, 100);
    }

    private static Stream<Integer> rejectedPageSizes() {
        return Stream.of(0, 101);
    }

    private static Stream<Arguments> transactionStateDecisions() {
        return Stream.of(
                Arguments.of(
                        TransactionProcessingStatus.RECEIVED,
                        IdempotencyRecoveryDecision.PROCESSING_INDETERMINATE
                ),
                Arguments.of(
                        TransactionProcessingStatus.ANALYZING,
                        IdempotencyRecoveryDecision.PROCESSING_INDETERMINATE
                ),
                Arguments.of(
                        TransactionProcessingStatus.ANALYZED,
                        IdempotencyRecoveryDecision.FINALIZATION_INCOMPLETE
                ),
                Arguments.of(
                        TransactionProcessingStatus.FAILED,
                        IdempotencyRecoveryDecision.CONFIRMED_DOMAIN_FAILURE
                )
        );
    }

    private static Stream<Arguments> approvedFinalCombinations() {
        return Stream.of(
                Arguments.of(
                        RiskLevel.LOW,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED
                ),
                Arguments.of(
                        RiskLevel.MEDIUM,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED_WITH_MONITORING
                ),
                Arguments.of(
                        RiskLevel.HIGH,
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                        RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
                ),
                Arguments.of(
                        RiskLevel.CRITICAL,
                        TransactionProcessingStatus.HELD,
                        RiskResponseOutcome.HELD
                )
        );
    }
}
