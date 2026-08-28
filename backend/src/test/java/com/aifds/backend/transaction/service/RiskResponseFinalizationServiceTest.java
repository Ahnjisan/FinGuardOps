package com.aifds.backend.transaction.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.service.FraudCaseLinkResult;
import com.aifds.backend.fraudcase.service.FraudCasePersistenceService;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskResponseFinalizationServiceTest {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-24T01:00:00Z");
    private static final Instant LINKED_AT =
            Instant.parse("2026-08-24T01:00:03Z");
    private static final String TRACE_ID = "trace_finalization_test_01";

    @Mock
    private FinancialTransactionRepository transactionRepository;

    @Mock
    private FraudCasePersistenceService fraudCasePersistenceService;

    @Mock
    private AuditLogPersistenceService auditLogPersistenceService;

    @Mock
    private TransactionProcessingMetricsRecorder metricsRecorder;

    private RiskResponseFinalizationService service;

    @BeforeEach
    void setUp() {
        service = new RiskResponseFinalizationService(
                transactionRepository,
                fraudCasePersistenceService,
                auditLogPersistenceService,
                metricsRecorder
        );
    }

    @ParameterizedTest
    @MethodSource("riskFinalizations")
    void finalizesEveryRiskLevelWithExactStatusOutcomeAndCaseResult(
            RiskLevel riskLevel,
            TransactionProcessingStatus expectedStatus,
            RiskResponseOutcome expectedOutcome,
            boolean caseRequired
    ) {
        FinancialTransaction transaction = analyzedTransaction(riskLevel);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        UUID caseId = caseRequired ? UUID.randomUUID() : null;
        if (caseRequired) {
            when(fraudCasePersistenceService
                    .createOrReuseForHighRiskTransaction(
                            transaction.getTransactionId()
                    )).thenReturn(caseResult(transaction, caseId, true));
        }

        RiskResponseFinalizationResult result = service.finalizeRiskResponse(
                transaction.getTransactionId()
        );

        assertThat(result.transactionId())
                .isEqualTo(transaction.getTransactionId());
        assertThat(result.adoptedDetectionResultId()).isEqualTo(
                transaction.getAdoptedDetectionResult()
                        .getDetectionResultId()
        );
        assertThat(result.riskLevel()).isEqualTo(riskLevel);
        assertThat(result.processingStatus()).isEqualTo(expectedStatus);
        assertThat(result.riskResponseOutcome()).isEqualTo(expectedOutcome);
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.caseCreated()).isEqualTo(caseRequired);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(expectedStatus);
        assertThat(transaction.getRiskResponseOutcome())
                .isEqualTo(expectedOutcome);
        verify(transactionRepository).saveAndFlush(transaction);
        verify(auditLogPersistenceService, times(caseRequired ? 4 : 2))
                .append(any(AuditLogDraft.class));
        verifyNoInteractions(metricsRecorder);
    }

    @ParameterizedTest
    @MethodSource("risksWithoutCases")
    void doesNotCallCaseServiceForRiskThatDoesNotRequireCase(
            RiskLevel riskLevel
    ) {
        FinancialTransaction transaction = analyzedTransaction(riskLevel);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));

        RiskResponseFinalizationResult result = service.finalizeRiskResponse(
                transaction.getTransactionId()
        );

        assertThat(result.caseId()).isNull();
        assertThat(result.caseCreated()).isFalse();
        verifyNoInteractions(fraudCasePersistenceService);
    }

    @Test
    void distinguishesNewAndReusedCaseWithoutDuplicateCaseAudits() {
        FinancialTransaction transaction = analyzedTransaction(RiskLevel.HIGH);
        UUID caseId = UUID.randomUUID();
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        when(fraudCasePersistenceService
                .createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )).thenReturn(caseResult(transaction, caseId, false));

        RiskResponseFinalizationResult result = service.finalizeRiskResponse(
                transaction.getTransactionId()
        );

        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.caseCreated()).isFalse();
        ArgumentCaptor<AuditLogDraft> captor = ArgumentCaptor.forClass(
                AuditLogDraft.class
        );
        verify(auditLogPersistenceService, times(2))
                .append(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AuditLogDraft::action)
                .containsExactly(
                        AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                        AuditAction.TRANSACTION_STATUS_CHANGED
                );
    }

    @Test
    void appendsExactAuditDraftsInApprovedOrderAfterTransactionFlush() {
        FinancialTransaction transaction = analyzedTransaction(
                RiskLevel.CRITICAL
        );
        DetectionResult detectionResult = transaction
                .getAdoptedDetectionResult();
        UUID caseId = UUID.randomUUID();
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        when(fraudCasePersistenceService
                .createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )).thenReturn(caseResult(transaction, caseId, true));

        service.finalizeRiskResponse(transaction.getTransactionId());

        ArgumentCaptor<AuditLogDraft> captor = ArgumentCaptor.forClass(
                AuditLogDraft.class
        );
        verify(auditLogPersistenceService, times(4))
                .append(captor.capture());
        List<AuditLogDraft> drafts = captor.getAllValues();

        assertCaseDraft(
                drafts.get(0),
                AuditAction.CASE_CREATED,
                caseId,
                transaction,
                detectionResult,
                "caseStatus",
                "OPEN"
        );
        assertCaseDraft(
                drafts.get(1),
                AuditAction.CASE_TRANSACTION_LINKED,
                caseId,
                transaction,
                detectionResult,
                "linked",
                true
        );
        assertTransactionDraft(
                drafts.get(2),
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                transaction,
                detectionResult,
                null,
                object("riskResponseOutcome", "HELD")
        );
        assertTransactionDraft(
                drafts.get(3),
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                transaction,
                detectionResult,
                object("processingStatus", "ANALYZED"),
                object("processingStatus", "HELD")
        );

        InOrder order = inOrder(
                transactionRepository,
                fraudCasePersistenceService,
                auditLogPersistenceService
        );
        order.verify(transactionRepository).findByTransactionIdForUpdate(
                transaction.getTransactionId()
        );
        order.verify(fraudCasePersistenceService)
                .createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                );
        order.verify(transactionRepository).saveAndFlush(transaction);
        order.verify(auditLogPersistenceService).append(drafts.get(0));
        order.verify(auditLogPersistenceService).append(drafts.get(1));
        order.verify(auditLogPersistenceService).append(drafts.get(2));
        order.verify(auditLogPersistenceService).append(drafts.get(3));
    }

    @Test
    void rejectsIneligibleTransactionsBeforeCaseMutationAndAuditing() {
        FinancialTransaction received = transaction();
        assertEligibilityFailure(
                received,
                "Transaction processing status must be ANALYZED"
        );

        FinancialTransaction missingAdopted = analyzedTransaction(
                RiskLevel.HIGH
        );
        ReflectionTestUtils.setField(
                missingAdopted,
                "adoptedDetectionResult",
                null
        );
        assertEligibilityFailure(
                missingAdopted,
                "Transaction must have an adopted detection result"
        );

        FinancialTransaction pendingOwner = transaction();
        DetectionResult pending = pendingResult(pendingOwner);
        pendingOwner.startAnalysis();
        ReflectionTestUtils.setField(
                pendingOwner,
                "adoptedDetectionResult",
                pending
        );
        ReflectionTestUtils.setField(
                pendingOwner,
                "riskLevel",
                RiskLevel.HIGH
        );
        ReflectionTestUtils.setField(
                pendingOwner,
                "processingStatus",
                TransactionProcessingStatus.ANALYZED
        );
        assertEligibilityFailure(
                pendingOwner,
                "Adopted detection result must be COMPLETED"
        );

        FinancialTransaction wrongOwner = analyzedTransaction(RiskLevel.HIGH);
        FinancialTransaction other = analyzedTransaction(RiskLevel.HIGH);
        ReflectionTestUtils.setField(
                wrongOwner,
                "adoptedDetectionResult",
                other.getAdoptedDetectionResult()
        );
        assertEligibilityFailure(
                wrongOwner,
                "Adopted detection result must belong to the transaction"
        );

        FinancialTransaction missingRisk = analyzedTransaction(RiskLevel.HIGH);
        ReflectionTestUtils.setField(missingRisk, "riskLevel", null);
        assertEligibilityFailure(
                missingRisk,
                "Transaction risk level must not be null"
        );

        FinancialTransaction mismatchedRisk = analyzedTransaction(
                RiskLevel.HIGH
        );
        ReflectionTestUtils.setField(
                mismatchedRisk,
                "riskLevel",
                RiskLevel.CRITICAL
        );
        assertEligibilityFailure(
                mismatchedRisk,
                "Transaction risk level must match the adopted result"
        );

        FinancialTransaction existingOutcome = analyzedTransaction(
                RiskLevel.HIGH
        );
        ReflectionTestUtils.setField(
                existingOutcome,
                "riskResponseOutcome",
                RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
        );
        assertEligibilityFailure(
                existingOutcome,
                "Risk response outcome must not already be finalized"
        );
    }

    @Test
    void requiresValidCaseResultBeforeChangingTransaction() {
        FinancialTransaction transaction = analyzedTransaction(RiskLevel.HIGH);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        when(fraudCasePersistenceService
                .createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )).thenReturn(null);

        assertThatThrownBy(() -> service.finalizeRiskResponse(
                transaction.getTransactionId()
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("A required case result must not be null");

        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(transaction.getRiskResponseOutcome()).isNull();
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogPersistenceService);
    }

    @Test
    void propagatesCaseFailureWithoutChangingTransaction() {
        FinancialTransaction transaction = analyzedTransaction(
                RiskLevel.CRITICAL
        );
        IllegalStateException failure = new IllegalStateException(
                "case failure"
        );
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));
        doThrow(failure).when(fraudCasePersistenceService)
                .createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                );

        assertThatThrownBy(() -> service.finalizeRiskResponse(
                transaction.getTransactionId()
        )).isSameAs(failure);
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(transaction.getRiskResponseOutcome()).isNull();
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogPersistenceService);
    }

    @Test
    void rejectsTerminalRecallBeforeCaseAndAdditionalAudits() {
        FinancialTransaction transaction = analyzedTransaction(RiskLevel.LOW);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));

        service.finalizeRiskResponse(transaction.getTransactionId());

        assertThatThrownBy(() -> service.finalizeRiskResponse(
                transaction.getTransactionId()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Transaction processing status must be ANALYZED"
                );
        verify(auditLogPersistenceService, times(2))
                .append(any(AuditLogDraft.class));
        verifyNoInteractions(fraudCasePersistenceService);
    }

    @Test
    void usesDefaultRequiredTransactionAndImmutableEntityFreeResult()
            throws Exception {
        Method method = RiskResponseFinalizationService.class.getMethod(
                "finalizeRiskResponse",
                UUID.class
        );
        Transactional transactional = method.getAnnotation(
                Transactional.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRED);
        assertThat(transactional.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(RiskResponseFinalizationResult.class.isRecord()).isTrue();
        assertThat(Stream.of(
                RiskResponseFinalizationResult.class.getRecordComponents()
        ).map(RecordComponent::getType)).doesNotContain(
                FinancialTransaction.class,
                DetectionResult.class
        );
    }

    @Test
    void acceptsEveryApprovedResultCombination() {
        UUID highCaseId = UUID.randomUUID();
        UUID criticalCaseId = UUID.randomUUID();

        assertThat(List.of(
                result(
                        RiskLevel.LOW,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED,
                        null,
                        false
                ),
                result(
                        RiskLevel.MEDIUM,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED_WITH_MONITORING,
                        null,
                        false
                ),
                result(
                        RiskLevel.HIGH,
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                        RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                        highCaseId,
                        true
                ),
                result(
                        RiskLevel.HIGH,
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                        RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                        highCaseId,
                        false
                ),
                result(
                        RiskLevel.CRITICAL,
                        TransactionProcessingStatus.HELD,
                        RiskResponseOutcome.HELD,
                        criticalCaseId,
                        true
                ),
                result(
                        RiskLevel.CRITICAL,
                        TransactionProcessingStatus.HELD,
                        RiskResponseOutcome.HELD,
                        criticalCaseId,
                        false
                )
        )).hasSize(6);
    }

    @Test
    void rejectsCaseIdForLowResult() {
        assertThatThrownBy(() -> result(
                RiskLevel.LOW,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                UUID.randomUUID(),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCaseCreatedForLowResult() {
        assertThatThrownBy(() -> result(
                RiskLevel.LOW,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                true
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongOutcomeForMediumResult() {
        assertThatThrownBy(() -> result(
                RiskLevel.MEDIUM,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingCaseIdForHighResult() {
        assertThatThrownBy(() -> result(
                RiskLevel.HIGH,
                TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                null,
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongStatusForHighResult() {
        assertThatThrownBy(() -> result(
                RiskLevel.HIGH,
                TransactionProcessingStatus.HELD,
                RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                UUID.randomUUID(),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongOutcomeForCriticalResult() {
        assertThatThrownBy(() -> result(
                RiskLevel.CRITICAL,
                TransactionProcessingStatus.HELD,
                RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                UUID.randomUUID(),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullRequiredResultComponents() {
        assertThatThrownBy(() -> new RiskResponseFinalizationResult(
                null,
                UUID.randomUUID(),
                RiskLevel.LOW,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                false
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RiskResponseFinalizationResult(
                UUID.randomUUID(),
                null,
                RiskLevel.LOW,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                false
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RiskResponseFinalizationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                false
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RiskResponseFinalizationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                RiskLevel.LOW,
                null,
                RiskResponseOutcome.APPROVED,
                null,
                false
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RiskResponseFinalizationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                RiskLevel.LOW,
                TransactionProcessingStatus.APPROVED,
                null,
                null,
                false
        )).isInstanceOf(NullPointerException.class);
    }

    private void assertEligibilityFailure(
            FinancialTransaction transaction,
            String expectedMessage
    ) {
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> service.finalizeRiskResponse(
                transaction.getTransactionId()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);

        verify(transactionRepository, never()).saveAndFlush(transaction);
        verifyNoInteractions(
                fraudCasePersistenceService,
                auditLogPersistenceService
        );
        org.mockito.Mockito.clearInvocations(
                transactionRepository,
                fraudCasePersistenceService,
                auditLogPersistenceService
        );
    }

    private void assertCaseDraft(
            AuditLogDraft draft,
            AuditAction action,
            UUID caseId,
            FinancialTransaction transaction,
            DetectionResult result,
            String afterField,
            Object afterValue
    ) {
        assertThat(draft.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(draft.actorId()).isEqualTo(AuditLog.SYSTEM_ACTOR_ID);
        assertThat(draft.action()).isEqualTo(action);
        assertThat(draft.reasonCode())
                .isEqualTo(AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY);
        assertThat(draft.targetType()).isEqualTo(AuditTargetType.FRAUD_CASE);
        assertThat(draft.targetId()).isEqualTo(caseId);
        assertThat(draft.transactionId())
                .isEqualTo(transaction.getTransactionId());
        assertThat(draft.caseId()).isEqualTo(caseId);
        assertThat(draft.traceId()).isEqualTo(TRACE_ID);
        assertThat(draft.beforeValueSummary()).isNull();
        assertThat(draft.afterValueSummary()).isEqualTo(
                afterValue instanceof Boolean booleanValue
                        ? object(afterField, booleanValue)
                        : object(afterField, afterValue.toString())
        );
        assertThat(draft.metadata()).isEqualTo(detectionMetadata(result));
    }

    private void assertTransactionDraft(
            AuditLogDraft draft,
            AuditAction action,
            AuditReasonCode reason,
            FinancialTransaction transaction,
            DetectionResult result,
            JsonNode before,
            ObjectNode after
    ) {
        assertThat(draft.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(draft.actorId()).isEqualTo(AuditLog.SYSTEM_ACTOR_ID);
        assertThat(draft.action()).isEqualTo(action);
        assertThat(draft.reasonCode()).isEqualTo(reason);
        assertThat(draft.targetType())
                .isEqualTo(AuditTargetType.FINANCIAL_TRANSACTION);
        assertThat(draft.targetId()).isEqualTo(transaction.getTransactionId());
        assertThat(draft.transactionId())
                .isEqualTo(transaction.getTransactionId());
        assertThat(draft.caseId()).isNull();
        assertThat(draft.traceId()).isEqualTo(TRACE_ID);
        assertThat(draft.beforeValueSummary()).isEqualTo(before);
        assertThat(draft.afterValueSummary()).isEqualTo(after);
        assertThat(draft.metadata()).isEqualTo(
                detectionMetadata(result)
                        .put("sourceRiskLevel", "CRITICAL")
        );
    }

    private static Stream<Arguments> riskFinalizations() {
        return Stream.of(
                Arguments.of(
                        RiskLevel.LOW,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED,
                        false
                ),
                Arguments.of(
                        RiskLevel.MEDIUM,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED_WITH_MONITORING,
                        false
                ),
                Arguments.of(
                        RiskLevel.HIGH,
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED,
                        RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED,
                        true
                ),
                Arguments.of(
                        RiskLevel.CRITICAL,
                        TransactionProcessingStatus.HELD,
                        RiskResponseOutcome.HELD,
                        true
                )
        );
    }

    private static Stream<RiskLevel> risksWithoutCases() {
        return Stream.of(RiskLevel.LOW, RiskLevel.MEDIUM);
    }

    private FinancialTransaction analyzedTransaction(RiskLevel riskLevel) {
        FinancialTransaction transaction = transaction();
        DetectionResult result = pendingResult(transaction);
        result.start(OCCURRED_AT.plusSeconds(1));
        result.complete(80, riskLevel, OCCURRED_AT.plusSeconds(2));
        transaction.startAnalysis();
        transaction.adoptDetectionResult(result);
        return transaction;
    }

    private DetectionResult pendingResult(FinancialTransaction transaction) {
        return DetectionResult.pending(
                transaction,
                1,
                "rule-set-v1",
                "scoring-v1",
                "feature-v1",
                null,
                transaction.getOccurredAt(),
                TRACE_ID
        );
    }

    private FinancialTransaction transaction() {
        return new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(10_000),
                "KRW",
                OCCURRED_AT,
                "customer_ref",
                "sender_ref",
                "recipient_ref",
                TransactionChannel.MOBILE_BANKING,
                "device_ref"
        );
    }

    private FraudCaseLinkResult caseResult(
            FinancialTransaction transaction,
            UUID caseId,
            boolean newlyCreated
    ) {
        return new FraudCaseLinkResult(
                caseId,
                transaction.getTransactionId(),
                FraudCaseStatus.OPEN,
                LINKED_AT,
                newlyCreated
        );
    }

    private RiskResponseFinalizationResult result(
            RiskLevel riskLevel,
            TransactionProcessingStatus processingStatus,
            RiskResponseOutcome riskResponseOutcome,
            UUID caseId,
            boolean caseCreated
    ) {
        return new RiskResponseFinalizationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                riskLevel,
                processingStatus,
                riskResponseOutcome,
                caseId,
                caseCreated
        );
    }

    private ObjectNode detectionMetadata(
            DetectionResult result
    ) {
        return JsonNodeFactory.instance.objectNode()
                .put(
                        "detectionResultId",
                        result.getDetectionResultId().toString()
                )
                .put(
                        "detectionResultVersion",
                        result.getDetectionResultVersion()
                );
    }

    private ObjectNode object(
            String field,
            String value
    ) {
        return JsonNodeFactory.instance.objectNode().put(field, value);
    }

    private ObjectNode object(
            String field,
            boolean value
    ) {
        return JsonNodeFactory.instance.objectNode().put(field, value);
    }
}
