package com.aifds.backend.scenario.auth;

import com.aifds.backend.audit.entity.*;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Immutable post-decision Mock authentication transition. */
@Service
public class MockAuthService {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private final FinancialTransactionRepository transactions;
    private final CaseTransactionRepository cases;
    private final FraudCaseRepository fraudCases;
    private final AuditLogPersistenceService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MockAuthService(FinancialTransactionRepository transactions,
            CaseTransactionRepository cases, FraudCaseRepository fraudCases,
            AuditLogPersistenceService audit,
            JdbcTemplate jdbc, Clock clock) {
        this.transactions = transactions;
        this.cases = cases;
        this.fraudCases = fraudCases;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public MockAuthResponse decide(UUID transactionId, MockAuthRequest request, String traceId) {
        if (transactionId == null || transactionId.version() != 4 || request == null
                || request.decisionId() == null || request.decisionId().version() != 4
                || !("SUCCESS".equals(request.result()) || "FAILURE".equals(request.result())
                        || "EXPIRE".equals(request.result()))) {
            throw new MockAuthException(HttpStatus.BAD_REQUEST, "MOCK_AUTH_INVALID_REQUEST");
        }
        FinancialTransaction tx = transactions.findByTransactionIdForUpdate(transactionId)
                .orElseThrow(() -> new MockAuthException(HttpStatus.NOT_FOUND,
                        "MOCK_AUTH_TRANSACTION_NOT_FOUND"));
        List<MockAuthResponse> previous = jdbc.query("""
                SELECT decision_id, requested_result, applied_result, decided_at
                FROM fds_mock_auth_decision WHERE transaction_id = ?
                """, (rs, n) -> {
                    UUID priorId = rs.getObject("decision_id", UUID.class);
                    if (!priorId.equals(request.decisionId())
                            || !rs.getString("requested_result").equals(request.result())) {
                        throw new MockAuthException(HttpStatus.CONFLICT,
                                "MOCK_AUTH_DECISION_CONFLICT");
                    }
                    String priorApplied = rs.getString("applied_result");
                    return new MockAuthResponse(transactionId, priorId,
                            priorApplied, "SUCCESS".equals(priorApplied)
                                    ? TransactionProcessingStatus.APPROVED.name()
                                    : TransactionProcessingStatus.HELD.name(),
                            rs.getTimestamp("decided_at").toInstant(), "LOCAL_AUTH_MOCK", "v1");
                }, tx.getId());
        if (!previous.isEmpty()) return previous.get(0);
        if (tx.getProcessingStatus() != TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                || tx.getAdoptedDetectionResult() == null
                || !("rule-ml-policy-v3".equals(
                        tx.getAdoptedDetectionResult().getScoringPolicyVersion())
                        || "rule-ml-policy-v4".equals(
                                tx.getAdoptedDetectionResult().getScoringPolicyVersion()))) {
            throw new MockAuthException(HttpStatus.CONFLICT, "MOCK_AUTH_STATE_CONFLICT");
        }
        var links = cases.findAllByTransactionPk(tx.getId());
        if (links.size() != 1) {
            throw new MockAuthException(HttpStatus.CONFLICT, "MOCK_AUTH_CASE_CONFLICT");
        }
        var linkedCaseId = links.get(0).getFraudCase().getCaseId();
        var lockedCases = fraudCases.findAllByCaseIdsForUpdate(List.of(linkedCaseId));
        if (lockedCases.size() != 1 || !lockedCases.get(0).isActive()) {
            throw new MockAuthException(HttpStatus.CONFLICT, "MOCK_AUTH_CASE_CONFLICT");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = tx.getUpdatedAt().plus(WINDOW);
        String applied;
        if (now.isAfter(deadline)) {
            applied = "EXPIRED";
        } else if ("EXPIRE".equals(request.result())) {
            throw new MockAuthException(HttpStatus.CONFLICT, "MOCK_AUTH_NOT_EXPIRED");
        } else {
            applied = request.result();
        }
        tx.decideAdditionalAuthentication("SUCCESS".equals(applied));
        transactions.saveAndFlush(tx);
        try {
            jdbc.update("""
                    INSERT INTO fds_mock_auth_decision
                      (decision_id, transaction_id, requested_result, applied_result,
                       source_code, source_version, decided_at)
                    VALUES (?, ?, ?, ?, 'LOCAL_AUTH_MOCK', 'v1', ?)
                    """, request.decisionId(), tx.getId(), request.result(), applied,
                    java.sql.Timestamp.from(now));
        } catch (DataIntegrityViolationException conflict) {
            throw new MockAuthException(HttpStatus.CONFLICT, "MOCK_AUTH_DECISION_CONFLICT");
        }
        audit.append(new AuditLogDraft(AuditActorType.SYSTEM, AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.TRANSACTION_STATUS_CHANGED, AuditReasonCode.MOCK_AUTH_DECIDED,
                AuditTargetType.FINANCIAL_TRANSACTION, transactionId, transactionId,
                links.get(0).getFraudCase().getCaseId(), traceId,
                JsonNodeFactory.instance.objectNode().put("processingStatus",
                        TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED.name()),
                JsonNodeFactory.instance.objectNode().put("processingStatus",
                        tx.getProcessingStatus().name()),
                JsonNodeFactory.instance.objectNode()
                        .put("sourceRiskLevel", tx.getRiskLevel().name())
                        .put("detectionResultId",
                                tx.getAdoptedDetectionResult().getDetectionResultId().toString())
                        .put("detectionResultVersion",
                                tx.getAdoptedDetectionResult().getDetectionResultVersion())));
        return new MockAuthResponse(transactionId, request.decisionId(), applied,
                tx.getProcessingStatus().name(), now, "LOCAL_AUTH_MOCK", "v1");
    }
}
