package com.aifds.backend.scenario.auth;

import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MockAuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    @Mock private FinancialTransactionRepository transactions;
    @Mock private CaseTransactionRepository links;
    @Mock private FraudCaseRepository fraudCases;
    @Mock private AuditLogPersistenceService audit;
    @Mock private JdbcTemplate jdbc;
    @Mock private FinancialTransaction tx;
    @Mock private DetectionResult detection;
    @Mock private CaseTransaction link;
    @Mock private FraudCase fraudCase;
    private MockAuthService service;
    private UUID transactionId;
    private UUID caseId;
    private AtomicReference<TransactionProcessingStatus> status;

    @BeforeEach
    void setUp() {
        transactionId = UUID.randomUUID();
        caseId = UUID.randomUUID();
        status = new AtomicReference<>(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        service = new MockAuthService(transactions, links, fraudCases, audit, jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void eligible(Instant lastChange, boolean active) {
        when(transactions.findByTransactionIdForUpdate(transactionId)).thenReturn(Optional.of(tx));
        when(tx.getId()).thenReturn(11L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(11L))).thenReturn(List.of());
        when(tx.getProcessingStatus()).thenAnswer(ignored -> status.get());
        when(tx.getAdoptedDetectionResult()).thenReturn(detection);
        when(detection.getScoringPolicyVersion()).thenReturn("rule-ml-policy-v3");
        when(links.findAllByTransactionPk(11L)).thenReturn(List.of(link));
        when(link.getFraudCase()).thenReturn(fraudCase);
        when(fraudCase.getCaseId()).thenReturn(caseId);
        when(fraudCases.findAllByCaseIdsForUpdate(List.of(caseId)))
                .thenReturn(List.of(fraudCase));
        when(fraudCase.isActive()).thenReturn(active);
        if (active) when(tx.getUpdatedAt()).thenReturn(lastChange);
    }

    @Test
    void successChangesOnlySeparateTransactionStateAfterLockedActiveCase() {
        eligible(NOW.minusSeconds(600), true);
        doAnswer(invocation -> {
            status.set(TransactionProcessingStatus.APPROVED);
            return null;
        }).when(tx).decideAdditionalAuthentication(true);
        when(tx.getRiskLevel()).thenReturn(com.aifds.backend.detection.entity.RiskLevel.HIGH);
        when(detection.getDetectionResultId()).thenReturn(UUID.randomUUID());
        when(detection.getDetectionResultVersion()).thenReturn(1);
        var response = service.decide(transactionId,
                new MockAuthRequest(UUID.randomUUID(), "SUCCESS"), "trace-384");
        assertThat(response.appliedResult()).isEqualTo("SUCCESS");
        assertThat(response.processingStatus()).isEqualTo("APPROVED");
        verify(fraudCases).findAllByCaseIdsForUpdate(List.of(caseId));
        verify(tx).decideAdditionalAuthentication(true);
        verify(transactions).saveAndFlush(tx);
        verify(audit).append(any());
        verify(tx, never()).adoptDetectionResult(any());
    }

    @Test
    void expiredSuccessFailsClosedIntoHeld() {
        eligible(NOW.minusSeconds(601), true);
        doAnswer(invocation -> {
            status.set(TransactionProcessingStatus.HELD);
            return null;
        }).when(tx).decideAdditionalAuthentication(false);
        when(tx.getRiskLevel()).thenReturn(com.aifds.backend.detection.entity.RiskLevel.HIGH);
        when(detection.getDetectionResultId()).thenReturn(UUID.randomUUID());
        when(detection.getDetectionResultVersion()).thenReturn(1);
        var response = service.decide(transactionId,
                new MockAuthRequest(UUID.randomUUID(), "SUCCESS"), "trace-384");
        assertThat(response.appliedResult()).isEqualTo("EXPIRED");
        assertThat(response.processingStatus()).isEqualTo("HELD");
        verify(tx).decideAdditionalAuthentication(false);
    }

    @Test
    void closedCaseRejectsCommandBeforeStateOrAuditChanges() {
        eligible(NOW.minusSeconds(60), false);
        assertThatThrownBy(() -> service.decide(transactionId,
                new MockAuthRequest(UUID.randomUUID(), "SUCCESS"), "trace-384"))
                .isInstanceOf(MockAuthException.class)
                .hasMessage("MOCK_AUTH_CASE_CONFLICT");
        verify(tx, never()).decideAdditionalAuthentication(anyBoolean());
        verify(transactions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void globallyReusedDecisionIdIsConflictAndNeverAudited() {
        eligible(NOW.minusSeconds(60), true);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("unique decision_id"));
        assertThatThrownBy(() -> service.decide(transactionId,
                new MockAuthRequest(UUID.randomUUID(), "FAILURE"), "trace-384"))
                .isInstanceOf(MockAuthException.class)
                .hasMessage("MOCK_AUTH_DECISION_CONFLICT");
        verifyNoInteractions(audit);
    }

}
