package com.aifds.backend.fraudcase.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseConsistencyException;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.policy.RiskResponseDecisionPolicy;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FraudCasePersistenceService {

    private static final Set<FraudCaseStatus> ACTIVE_STATUSES = Set.of(
            FraudCaseStatus.OPEN,
            FraudCaseStatus.IN_REVIEW,
            FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED
    );

    private final FinancialTransactionRepository transactionRepository;
    private final FraudCaseRepository fraudCaseRepository;
    private final CaseTransactionRepository caseTransactionRepository;
    private final Clock clock;
    private final RiskResponseDecisionPolicy decisionPolicy;

    public FraudCasePersistenceService(
            FinancialTransactionRepository transactionRepository,
            FraudCaseRepository fraudCaseRepository,
            CaseTransactionRepository caseTransactionRepository,
            Clock clock
    ) {
        this.transactionRepository = transactionRepository;
        this.fraudCaseRepository = fraudCaseRepository;
        this.caseTransactionRepository = caseTransactionRepository;
        this.clock = clock;
        this.decisionPolicy = new RiskResponseDecisionPolicy();
    }

    @Transactional
    public FraudCaseLinkResult createOrReuseForHighRiskTransaction(
            UUID transactionId
    ) {
        UUID requestedTransactionId = Objects.requireNonNull(
                transactionId,
                "transactionId must not be null"
        );
        FinancialTransaction transaction = transactionRepository
                .findByTransactionIdForUpdate(requestedTransactionId)
                .orElseThrow(TransactionNotFoundException::new);
        validateEligible(transaction);

        List<UUID> activeCaseIds = caseTransactionRepository
                .findActiveCaseIdsByTransactionPk(
                        transaction.getId(),
                        ACTIVE_STATUSES
                );
        if (activeCaseIds.size() > 1) {
            throw new FraudCaseConsistencyException(
                    "Transaction has multiple active cases"
            );
        }
        if (activeCaseIds.size() == 1) {
            return lockAndReuse(
                    transaction,
                    activeCaseIds
            );
        }
        return createAndLink(transaction);
    }

    private FraudCaseLinkResult lockAndReuse(
            FinancialTransaction transaction,
            List<UUID> activeCaseIds
    ) {
        List<FraudCase> lockedCases = fraudCaseRepository
                .findAllByCaseIdsForUpdate(activeCaseIds);
        if (lockedCases.size() != 1) {
            throw new FraudCaseConsistencyException(
                    "Active case could not be locked exactly once"
            );
        }
        FraudCase fraudCase = lockedCases.get(0);
        if (!fraudCase.getCaseId().equals(activeCaseIds.get(0))
                || !fraudCase.isActive()) {
            throw new FraudCaseConsistencyException(
                    "Locked case does not match the active relationship"
            );
        }

        List<CaseTransaction> lockedLinks = caseTransactionRepository
                .findAllByTransactionAndCaseIdsForUpdate(
                        transaction.getId(),
                        activeCaseIds
                );
        if (lockedLinks.size() != 1) {
            throw new FraudCaseConsistencyException(
                    "Active case relationship could not be locked exactly once"
            );
        }
        CaseTransaction link = lockedLinks.get(0);
        if (!link.belongsTo(fraudCase, transaction)) {
            throw new FraudCaseConsistencyException(
                    "Active case relationship does not match the transaction"
            );
        }
        return result(fraudCase, transaction, link, false);
    }

    private FraudCaseLinkResult createAndLink(
            FinancialTransaction transaction
    ) {
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        FraudCase fraudCase = fraudCaseRepository.saveAndFlush(
                FraudCase.open(UUID.randomUUID(), createdAt)
        );
        CaseTransaction link = caseTransactionRepository.saveAndFlush(
                CaseTransaction.link(fraudCase, transaction, createdAt)
        );
        return result(fraudCase, transaction, link, true);
    }

    private FraudCaseLinkResult result(
            FraudCase fraudCase,
            FinancialTransaction transaction,
            CaseTransaction link,
            boolean newlyCreated
    ) {
        return new FraudCaseLinkResult(
                fraudCase.getCaseId(),
                transaction.getTransactionId(),
                fraudCase.getCaseStatus(),
                link.getLinkedAt(),
                newlyCreated
        );
    }

    private void validateEligible(FinancialTransaction transaction) {
        if (transaction.getProcessingStatus()
                != TransactionProcessingStatus.ANALYZED) {
            throw new IllegalStateException(
                    "Transaction processing status must be ANALYZED"
            );
        }
        DetectionResult adoptedResult = transaction
                .getAdoptedDetectionResult();
        if (adoptedResult == null
                || adoptedResult.getAnalysisStatus()
                != DetectionAnalysisStatus.COMPLETED
                || !adoptedResult.belongsTo(transaction)) {
            throw new IllegalStateException(
                    "Transaction must have its completed detection result"
            );
        }
        if (transaction.getRiskLevel() == null) {
            throw new IllegalStateException(
                    "Transaction risk level must not be null"
            );
        }
        if (!decisionPolicy.decide(transaction.getRiskLevel()).caseRequired()) {
            throw new IllegalStateException(
                    "Transaction risk decision does not require a case"
            );
        }
    }
}
