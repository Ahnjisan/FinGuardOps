package com.aifds.backend.fraudcase.service;

import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseConsistencyException;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudCasePersistenceServiceTest {

    private static final Instant FIXED_NOW =
            Instant.parse("2026-08-18T03:04:05.123456789Z");
    private static final Instant EXPECTED_NOW =
            Instant.parse("2026-08-18T03:04:05.123456Z");

    @Mock
    private FinancialTransactionRepository transactionRepository;

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private CaseTransactionRepository caseTransactionRepository;

    @Mock
    private Clock clock;

    private FraudCasePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new FraudCasePersistenceService(
                transactionRepository,
                fraudCaseRepository,
                caseTransactionRepository,
                clock
        );
    }

    @ParameterizedTest
    @EnumSource(value = RiskLevel.class, names = {"HIGH", "CRITICAL"})
    void createsCaseAndFirstLinkForCaseRequiredDecision(RiskLevel riskLevel) {
        FinancialTransaction transaction = analyzedTransaction(riskLevel, 1L);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));
        when(caseTransactionRepository.findActiveCaseIdsByTransactionPk(
                eq(1L),
                anyCollection()
        )).thenReturn(List.of());
        when(fraudCaseRepository.saveAndFlush(any(FraudCase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(caseTransactionRepository.saveAndFlush(
                any(CaseTransaction.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));
        when(clock.instant()).thenReturn(FIXED_NOW);

        FraudCaseLinkResult result =
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                );

        assertThat(result.transactionId())
                .isEqualTo(transaction.getTransactionId());
        assertThat(result.caseId().version()).isEqualTo(4);
        assertThat(result.caseStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(result.linkedAt()).isEqualTo(EXPECTED_NOW);
        assertThat(result.newlyCreated()).isTrue();
        assertThat(transaction.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.ANALYZED);
        assertThat(transaction.getRiskResponseOutcome()).isNull();

        InOrder order = inOrder(
                transactionRepository,
                caseTransactionRepository,
                fraudCaseRepository
        );
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transaction.getTransactionId());
        order.verify(caseTransactionRepository)
                .findActiveCaseIdsByTransactionPk(
                        eq(1L),
                        anyCollection()
                );
        order.verify(fraudCaseRepository).saveAndFlush(any(FraudCase.class));
        order.verify(caseTransactionRepository)
                .saveAndFlush(any(CaseTransaction.class));
        verify(clock).instant();
    }

    @ParameterizedTest
    @EnumSource(value = RiskLevel.class, names = {"LOW", "MEDIUM"})
    void rejectsDecisionThatDoesNotRequireCase(RiskLevel riskLevel) {
        FinancialTransaction transaction = analyzedTransaction(riskLevel, 2L);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));

        assertThatIllegalStateException().isThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )
        ).withMessage("Transaction risk decision does not require a case");

        verify(caseTransactionRepository, never())
                .findActiveCaseIdsByTransactionPk(anyLong(), any());
        verify(fraudCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void returnsLockedExistingActiveRelationshipAsIdempotentSuccess() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.HIGH, 3L);
        FraudCase fraudCase = FraudCase.open(
                UUID.randomUUID(),
                EXPECTED_NOW
        );
        CaseTransaction link = CaseTransaction.link(
                fraudCase,
                transaction,
                EXPECTED_NOW
        );
        List<UUID> activeCaseIds = List.of(fraudCase.getCaseId());
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));
        when(caseTransactionRepository.findActiveCaseIdsByTransactionPk(
                eq(3L),
                anyCollection()
        )).thenReturn(activeCaseIds);
        when(fraudCaseRepository.findAllByCaseIdsForUpdate(activeCaseIds))
                .thenReturn(List.of(fraudCase));
        when(caseTransactionRepository
                .findAllByTransactionAndCaseIdsForUpdate(
                        3L,
                        activeCaseIds
                )).thenReturn(List.of(link));

        FraudCaseLinkResult result =
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                );

        assertThat(result.caseId()).isEqualTo(fraudCase.getCaseId());
        assertThat(result.linkedAt()).isEqualTo(link.getLinkedAt());
        assertThat(result.newlyCreated()).isFalse();
        verify(fraudCaseRepository, never()).saveAndFlush(any());

        InOrder order = inOrder(
                transactionRepository,
                caseTransactionRepository,
                fraudCaseRepository
        );
        order.verify(transactionRepository)
                .findByTransactionIdForUpdate(transaction.getTransactionId());
        order.verify(caseTransactionRepository)
                .findActiveCaseIdsByTransactionPk(
                        eq(3L),
                        anyCollection()
                );
        order.verify(fraudCaseRepository)
                .findAllByCaseIdsForUpdate(activeCaseIds);
        order.verify(caseTransactionRepository)
                .findAllByTransactionAndCaseIdsForUpdate(
                        3L,
                        activeCaseIds
                );
    }

    @Test
    void rejectsMultipleActiveCasesWithoutSelectingOne() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.CRITICAL, 4L);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));
        when(caseTransactionRepository.findActiveCaseIdsByTransactionPk(
                eq(4L),
                anyCollection()
        )).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )
        ).isInstanceOf(FraudCaseConsistencyException.class)
                .hasMessage("Transaction has multiple active cases");

        verify(fraudCaseRepository, never())
                .findAllByCaseIdsForUpdate(any());
        verify(fraudCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCaseThatIsNoLongerActiveAfterLock() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.HIGH, 5L);
        UUID caseId = UUID.randomUUID();
        FraudCase closedCase = org.mockito.Mockito.mock(FraudCase.class);
        when(closedCase.getCaseId()).thenReturn(caseId);
        when(closedCase.isActive()).thenReturn(false);
        List<UUID> activeCaseIds = List.of(caseId);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));
        when(caseTransactionRepository.findActiveCaseIdsByTransactionPk(
                eq(5L),
                anyCollection()
        )).thenReturn(activeCaseIds);
        when(fraudCaseRepository.findAllByCaseIdsForUpdate(activeCaseIds))
                .thenReturn(List.of(closedCase));

        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )
        ).isInstanceOf(FraudCaseConsistencyException.class)
                .hasMessage(
                        "Locked case does not match the active relationship"
                );

        verify(caseTransactionRepository, never())
                .findAllByTransactionAndCaseIdsForUpdate(anyLong(), any());
    }

    @Test
    void rejectsRelationshipThatDoesNotBelongToLockedTransaction() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.HIGH, 6L);
        FinancialTransaction other =
                analyzedTransaction(RiskLevel.HIGH, 7L);
        FraudCase fraudCase = FraudCase.open(
                UUID.randomUUID(),
                EXPECTED_NOW
        );
        CaseTransaction wrongLink = CaseTransaction.link(
                fraudCase,
                other,
                EXPECTED_NOW
        );
        List<UUID> activeCaseIds = List.of(fraudCase.getCaseId());
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));
        when(caseTransactionRepository.findActiveCaseIdsByTransactionPk(
                eq(6L),
                anyCollection()
        )).thenReturn(activeCaseIds);
        when(fraudCaseRepository.findAllByCaseIdsForUpdate(activeCaseIds))
                .thenReturn(List.of(fraudCase));
        when(caseTransactionRepository
                .findAllByTransactionAndCaseIdsForUpdate(
                        6L,
                        activeCaseIds
                )).thenReturn(List.of(wrongLink));

        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )
        ).isInstanceOf(FraudCaseConsistencyException.class)
                .hasMessage(
                        "Active case relationship does not match the transaction"
                );
    }

    @Test
    void rejectsTransactionThatIsNotAnalyzed() {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        ReflectionTestUtils.setField(transaction, "id", 8L);
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));

        assertThatIllegalStateException().isThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )
        ).withMessage("Transaction processing status must be ANALYZED");
    }

    @Test
    void rejectsAnalyzedTransactionWithoutAdoptedDetectionResult() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.HIGH, 9L);
        ReflectionTestUtils.setField(
                transaction,
                "adoptedDetectionResult",
                null
        );

        assertEligibilityFailureDoesNotPersist(
                transaction,
                "Transaction must have its completed detection result"
        );
    }

    @Test
    void rejectsAdoptedDetectionResultThatIsNotCompleted() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.HIGH, 10L);
        DetectionResult pendingResult = pendingDetectionResult(transaction, 2);
        ReflectionTestUtils.setField(
                transaction,
                "adoptedDetectionResult",
                pendingResult
        );

        assertEligibilityFailureDoesNotPersist(
                transaction,
                "Transaction must have its completed detection result"
        );
    }

    @Test
    void rejectsAdoptedDetectionResultOwnedByAnotherTransaction() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.HIGH, 11L);
        FinancialTransaction otherTransaction = transaction(UUID.randomUUID());
        DetectionResult otherResult = completedDetectionResult(
                otherTransaction,
                1,
                RiskLevel.HIGH
        );
        ReflectionTestUtils.setField(
                transaction,
                "adoptedDetectionResult",
                otherResult
        );

        assertEligibilityFailureDoesNotPersist(
                transaction,
                "Transaction must have its completed detection result"
        );
    }

    @Test
    void rejectsAnalyzedTransactionWithoutRiskLevel() {
        FinancialTransaction transaction =
                analyzedTransaction(RiskLevel.CRITICAL, 12L);
        ReflectionTestUtils.setField(transaction, "riskLevel", null);

        assertEligibilityFailureDoesNotPersist(
                transaction,
                "Transaction risk level must not be null"
        );
    }

    @Test
    void rejectsMissingTransactionAndNullInput() {
        UUID transactionId = UUID.randomUUID();
        when(transactionRepository.findByTransactionIdForUpdate(transactionId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(transactionId)
        ).isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(null)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("transactionId must not be null");
    }

    @Test
    void usesDefaultRequiredTransactionalBoundary() throws Exception {
        Method method = FraudCasePersistenceService.class.getMethod(
                "createOrReuseForHighRiskTransaction",
                UUID.class
        );
        Transactional transactional = method.getAnnotation(
                Transactional.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRED);
        assertThat(transactional.isolation()).isEqualTo(Isolation.DEFAULT);
    }

    private FinancialTransaction analyzedTransaction(
            RiskLevel riskLevel,
            long id
    ) {
        FinancialTransaction transaction = transaction(UUID.randomUUID());
        ReflectionTestUtils.setField(transaction, "id", id);
        DetectionResult result = DetectionResult.pending(
                transaction,
                1,
                "rule-set-v1",
                "scoring-v1",
                "feature-v1",
                null,
                transaction.getOccurredAt(),
                "trace_case_test_01"
        );
        result.start(transaction.getOccurredAt().plusSeconds(1));
        result.complete(
                90,
                riskLevel,
                transaction.getOccurredAt().plusSeconds(2)
        );
        transaction.startAnalysis();
        transaction.adoptDetectionResult(result);
        return transaction;
    }

    private DetectionResult pendingDetectionResult(
            FinancialTransaction transaction,
            int version
    ) {
        return DetectionResult.pending(
                transaction,
                version,
                "rule-set-v1",
                "scoring-v1",
                "feature-v1",
                null,
                transaction.getOccurredAt(),
                "trace_case_pending_01"
        );
    }

    private DetectionResult completedDetectionResult(
            FinancialTransaction transaction,
            int version,
            RiskLevel riskLevel
    ) {
        DetectionResult result = pendingDetectionResult(transaction, version);
        result.start(transaction.getOccurredAt().plusSeconds(1));
        result.complete(
                90,
                riskLevel,
                transaction.getOccurredAt().plusSeconds(2)
        );
        return result;
    }

    private void assertEligibilityFailureDoesNotPersist(
            FinancialTransaction transaction,
            String expectedMessage
    ) {
        TransactionProcessingStatus originalStatus =
                transaction.getProcessingStatus();
        RiskResponseOutcome originalOutcome =
                transaction.getRiskResponseOutcome();
        when(transactionRepository.findByTransactionIdForUpdate(
                transaction.getTransactionId()
        )).thenReturn(java.util.Optional.of(transaction));

        assertThatIllegalStateException().isThrownBy(() ->
                service.createOrReuseForHighRiskTransaction(
                        transaction.getTransactionId()
                )
        ).withMessage(expectedMessage);

        assertThat(transaction.getProcessingStatus()).isEqualTo(originalStatus);
        assertThat(transaction.getRiskResponseOutcome()).isEqualTo(
                originalOutcome
        );
        verifyNoInteractions(
                fraudCaseRepository,
                caseTransactionRepository,
                clock
        );
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(10_000),
                "KRW",
                Instant.parse("2026-08-18T01:00:00Z"),
                "customer_ref",
                "sender_ref",
                "recipient_ref",
                TransactionChannel.MOBILE_BANKING,
                "device_ref"
        );
    }
}
