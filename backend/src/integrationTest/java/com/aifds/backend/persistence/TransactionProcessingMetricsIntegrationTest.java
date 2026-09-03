package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.mock.ExternalRiskMockAdapter;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.observability.MicrometerTransactionProcessingMetricsRecorder;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.rule.client.dto.RuleAnalysisResultResponse;
import com.aifds.backend.rule.client.dto.RuleEvidenceResponse;
import com.aifds.backend.rule.client.dto.RuleId;
import com.aifds.backend.rule.client.dto.RuleRiskLevel;
import com.aifds.backend.rule.client.dto.RuleScoringResultResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.service.RuleVersionLifecycleService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.TransactionIntakeCompletionService;
import com.aifds.backend.transaction.service.TransactionIntakeWriter;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.validation.TransactionRequestValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles({"test", "external-risk-mock"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "finguardops.external-risk.mock.enabled=true",
                "finguardops.external-risk.mock.scenario=UNMATCHED"
        }
)
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(
        authorities = "transaction:intake"
)
class TransactionProcessingMetricsIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String PATH = "/api/v1/transactions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private IdempotencyRecoveryService recoveryService;
    @Autowired private TransactionRequestValidator requestValidator;
    @Autowired private TransactionIntakeWriter transactionIntakeWriter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FinancialTransactionRepository transactionRepository;
    @Autowired private RuleVersionRepository ruleVersionRepository;
    @Autowired private RuleVersionLifecycleService ruleVersionLifecycleService;

    @MockitoSpyBean
    private ExternalRiskMockAdapter externalRiskMockAdapter;

    @MockitoSpyBean
    private AuditLogPersistenceService auditLogPersistenceService;

    @MockitoSpyBean
    private TransactionIntakeCompletionService completionService;

    @MockitoSpyBean
    private TransactionProcessingMetricsRecorder metricsRecorder;

    @MockitoBean
    private RuleAnalysisHttpClient httpClient;

    @BeforeEach
    void clearMeters() {
        meterRegistry.clear();
    }

    @Test
    void committedSuccessAndReplayHaveExactIndependentMeterDeltas()
            throws Exception {
        RuleVersion version = publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID());
        String key = key("metrics-success");
        when(httpClient.analyzeV2(any(), anyString())).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        invocation.getArgument(1)
                )
        );

        perform(key, request, "trace_metrics_success_01")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processingStatus").value("APPROVED"));

        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "accepted"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTIONS_RECEIVED,
                1,
                "result", "received"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .EXTERNAL_RISK_OUTCOMES,
                1,
                "result", "unmatched",
                "failureCategory", "none"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_OUTCOMES,
                1,
                "result", "completed",
                "riskLevel", "LOW",
                "failureCategory", "none"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES,
                1,
                "status", "APPROVED",
                "riskLevel", "LOW",
                "failureCategory", "none"
        );
        assertTimer(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION,
                1,
                "status", "APPROVED"
        );
        double terminalBeforeReplay = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES,
                "status", "APPROVED"
        );
        long durationBeforeReplay = timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION,
                "status", "APPROVED"
        );

        perform(key, request, "trace_metrics_success_replay_01")
                .andExpect(status().isCreated());

        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "idempotent_replay"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .DUPLICATE_REQUESTS,
                1,
                "result", "completed"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTIONS_RECEIVED,
                1,
                "result", "received"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .EXTERNAL_RISK_OUTCOMES,
                1,
                "result", "unmatched"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_OUTCOMES,
                1,
                "result", "completed"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES,
                1,
                "status", "APPROVED"
        );
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES,
                "status", "APPROVED"
        )).isEqualTo(terminalBeforeReplay);
        assertThat(timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION,
                "status", "APPROVED"
        )).isEqualTo(durationBeforeReplay);
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), anyString());
        assertOnlyApprovedLowCardinalityTags();
    }

    @Test
    void validationInProgressAndConflictAreHttpAttemptMetricsOnly()
            throws Exception {
        TransactionCreateRequest request = request(UUID.randomUUID());

        perform("short", request, "trace_metrics_validation_01")
                .andExpect(status().isBadRequest());
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "validation_rejected"
        );

        String key = key("metrics-in-progress");
        ValidatedTransactionCommand command = requestValidator.validate(request);
        idempotencyService.claim(key, command.toFingerprintInput());
        perform(key, request, "trace_metrics_in_progress_01")
                .andExpect(status().isConflict());
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "in_progress"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .DUPLICATE_REQUESTS,
                1,
                "result", "in_progress"
        );

        TransactionCreateRequest changed = new TransactionCreateRequest(
                request.transactionId(),
                request.transactionType(),
                "10000001",
                request.currencyCode(),
                request.occurredAt(),
                request.externalCustomerRef(),
                request.senderAccountRef(),
                request.recipientAccountRef(),
                request.channel(),
                request.deviceRef()
        );
        perform(key, changed, "trace_metrics_conflict_01")
                .andExpect(status().isConflict());
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "conflict"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .IDEMPOTENCY_CONFLICTS,
                1,
                "result", "conflict"
        );
        assertThat(meterRegistry.find(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTIONS_RECEIVED
        ).counter()).isNull();
        verify(externalRiskMockAdapter, never()).lookup(any());
        verify(httpClient, never()).analyzeV2(any(), anyString());
    }

    @Test
    void externalRiskFailureReplayDoesNotRepeatBusinessMeters()
            throws Exception {
        TransactionCreateRequest request = request(UUID.randomUUID());
        String key = key("metrics-external-failure");
        doThrow(new ExternalRiskLookupException(
                ExternalRiskFailureCategory.TIMEOUT
        )).when(externalRiskMockAdapter).lookup(any());

        perform(key, request, "trace_metrics_external_failure_01")
                .andExpect(status().isServiceUnavailable());
        perform(key, request, "trace_metrics_external_failure_replay_01")
                .andExpect(status().isServiceUnavailable());

        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "external_risk_failed"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "idempotent_replay"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .DUPLICATE_REQUESTS,
                1,
                "result", "failed"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTIONS_RECEIVED,
                1,
                "result", "received"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .EXTERNAL_RISK_OUTCOMES,
                1,
                "result", "failed",
                "failureCategory", "TIMEOUT"
        );
        assertTimer(
                MicrometerTransactionProcessingMetricsRecorder
                        .EXTERNAL_RISK_DURATION,
                1,
                "result", "failed"
        );
        assertThat(meterRegistry.find(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_OUTCOMES
        ).counter()).isNull();
        assertThat(meterRegistry.find(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        ).counter()).isNull();
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, never()).analyzeV2(any(), anyString());
    }

    @Test
    void ruleFailureRecordsRuleAndFailedTerminalExactlyOnce()
            throws Exception {
        publishAmountRule();
        TransactionCreateRequest request = request(UUID.randomUUID());
        when(httpClient.analyzeV2(any(), anyString())).thenThrow(
                new IllegalStateException("client programming failure")
        );

        perform(
                key("metrics-rule-failure"),
                request,
                "trace_metrics_rule_failure_01"
        ).andExpect(status().isServiceUnavailable());

        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                1,
                "result", "rule_failed"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_OUTCOMES,
                1,
                "result", "failed",
                "failureCategory", "RULE_ANALYSIS_HTTP_CALL_FAILED"
        );
        assertCounter(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES,
                1,
                "status", "FAILED",
                "riskLevel", "unknown",
                "failureCategory", "RULE_ANALYSIS_HTTP_CALL_FAILED"
        );
        assertTimer(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_DURATION,
                1,
                "result", "failed"
        );
        assertTimer(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION,
                1,
                "status", "FAILED"
        );
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), anyString());
    }

    @Test
    void receivedRollbackDoesNotRecordReceivedOrPersistTransaction() {
        TransactionCreateRequest request = request(UUID.randomUUID());
        ValidatedTransactionCommand command = requestValidator.validate(request);
        String key = key("metrics-received-rollback");
        IdempotencyClaimResult claim = idempotencyService.claim(
                key,
                command.toFingerprintInput()
        );
        assertThat(claim).isInstanceOf(IdempotencyClaimResult.Acquired.class);
        long recordId = ((IdempotencyClaimResult.Acquired) claim).recordId();
        double before = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTIONS_RECEIVED,
                "result", "received"
        );

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    transactionIntakeWriter.saveAndLink(recordId, command);
                    status.setRollbackOnly();
                }
        );

        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTIONS_RECEIVED,
                "result", "received"
        )).isEqualTo(before);
        assertThat(transactionRepository.findByTransactionId(
                command.transactionId()
        )).isEmpty();
        assertThat(idempotencyStatus(key)).isEqualTo(
                IdempotencyProcessingStatus.IN_PROGRESS.name()
        );
    }

    @Test
    void finalizationRollbackRecordsOnlyFinalizationFailedIntake()
            throws Exception {
        RuleVersion version = publishAmountRule();
        stubSuccessfulRule(version);
        TransactionCreateRequest request = request(UUID.randomUUID());
        String key = key("metrics-finalization-rollback");
        double intakeBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "finalization_failed"
        );
        double terminalBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        );
        long durationBefore = timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        );
        doThrow(new IllegalStateException())
                .when(auditLogPersistenceService).append(any());

        perform(key, request, "trace_metrics_finalization_rollback_01")
                .andExpect(status().isInternalServerError());

        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "finalization_failed"
        ) - intakeBefore).isEqualTo(1);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        )).isEqualTo(terminalBefore);
        assertThat(timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        )).isEqualTo(durationBefore);
        assertThat(transactionStatus(request.transactionId()))
                .isEqualTo(TransactionProcessingStatus.ANALYZED.name());
        assertThat(idempotencyStatus(key)).isEqualTo(
                IdempotencyProcessingStatus.IN_PROGRESS.name()
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log",
                Integer.class
        )).isZero();
    }

    @Test
    void completionFailureKeepsTerminalCommitAndRecoveryReplayDoesNotRecount()
            throws Exception {
        RuleVersion version = publishAmountRule();
        stubSuccessfulRule(version);
        TransactionCreateRequest request = request(UUID.randomUUID());
        String key = key("metrics-completion-recovery");
        doThrow(new IllegalStateException()).when(completionService).complete(
                anyLong(), any(), any()
        );
        double completionBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "completion_failed"
        );
        double terminalBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        );
        long durationBefore = timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        );

        perform(key, request, "trace_metrics_completion_failure_01")
                .andExpect(status().isInternalServerError());

        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "completion_failed"
        ) - completionBefore).isEqualTo(1);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        ) - terminalBefore).isEqualTo(1);
        assertThat(timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        ) - durationBefore).isEqualTo(1);
        assertThat(transactionStatus(request.transactionId()))
                .isEqualTo(TransactionProcessingStatus.APPROVED.name());
        assertThat(idempotencyStatus(key)).isEqualTo(
                IdempotencyProcessingStatus.IN_PROGRESS.name()
        );

        double externalAfterFailure = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .EXTERNAL_RISK_OUTCOMES
        );
        double ruleAfterFailure = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_OUTCOMES
        );
        double terminalAfterFailure = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        );
        long durationAfterFailure = timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        );
        double duplicateBeforeReplay = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .DUPLICATE_REQUESTS,
                "result", "completed"
        );
        long recordId = idempotencyRecordId(key);
        assertThat(recoveryService.recover(
                recordId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        ).decision()).isEqualTo(
                IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP
        );

        perform(key, request, "trace_metrics_recovered_replay_01")
                .andExpect(status().isCreated());

        assertThat(idempotencyStatus(key)).isEqualTo(
                IdempotencyProcessingStatus.COMPLETED.name()
        );
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .EXTERNAL_RISK_OUTCOMES
        )).isEqualTo(externalAfterFailure);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .RULE_ANALYSIS_OUTCOMES
        )).isEqualTo(ruleAfterFailure);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        )).isEqualTo(terminalAfterFailure);
        assertThat(timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        )).isEqualTo(durationAfterFailure);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .DUPLICATE_REQUESTS,
                "result", "completed"
        ) - duplicateBeforeReplay).isEqualTo(1);
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), anyString());
    }

    @Test
    void afterCommitRecorderFailureCannotChangeCommittedHttpSuccess()
            throws Exception {
        RuleVersion version = publishAmountRule();
        stubSuccessfulRule(version);
        TransactionCreateRequest request = request(UUID.randomUUID());
        String key = key("metrics-recorder-failure");
        doThrow(new IllegalStateException()).when(metricsRecorder)
                .recordTransactionTerminal(any(), any(), any(), any());
        double intakeBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "accepted"
        );
        double terminalBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        );
        long durationBefore = timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        );

        perform(key, request, "trace_metrics_recorder_failure_01")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processingStatus").value("APPROVED"));

        assertThat(transactionStatus(request.transactionId()))
                .isEqualTo(TransactionProcessingStatus.APPROVED.name());
        assertThat(idempotencyStatus(key)).isEqualTo(
                IdempotencyProcessingStatus.COMPLETED.name()
        );
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "accepted"
        ) - intakeBefore).isEqualTo(1);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_OUTCOMES
        )).isEqualTo(terminalBefore);
        assertThat(timerCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .TRANSACTION_PROCESSING_DURATION
        )).isEqualTo(durationBefore);
        verify(metricsRecorder, times(1)).recordTransactionTerminal(
                any(), any(), any(), any()
        );
    }

    @Test
    void domainDuplicateAndIdempotencyConflictShareIntakeConflictResult()
            throws Exception {
        RuleVersion version = publishAmountRule();
        stubSuccessfulRule(version);
        TransactionCreateRequest request = request(UUID.randomUUID());
        String firstKey = key("metrics-duplicate-original");
        String duplicateKey = key("metrics-domain-duplicate");
        perform(firstKey, request, "trace_metrics_duplicate_original_01")
                .andExpect(status().isCreated());
        double conflictBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "conflict"
        );
        double idempotencyConflictBefore = counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .IDEMPOTENCY_CONFLICTS,
                "result", "conflict"
        );

        perform(duplicateKey, request, "trace_metrics_domain_duplicate_01")
                .andExpect(status().isConflict());

        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "conflict"
        ) - conflictBefore).isEqualTo(1);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .IDEMPOTENCY_CONFLICTS,
                "result", "conflict"
        )).isEqualTo(idempotencyConflictBefore);
        assertThat(idempotencyStatus(duplicateKey)).isEqualTo(
                IdempotencyProcessingStatus.FAILED.name()
        );
        assertThat(idempotencyFailureCode(duplicateKey))
                .isEqualTo("DUPLICATE_TRANSACTION");

        perform(
                firstKey,
                withAmount(request, "10000001"),
                "trace_metrics_idempotency_conflict_01"
        ).andExpect(status().isConflict());

        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder.INTAKE_OUTCOMES,
                "result", "conflict"
        ) - conflictBefore).isEqualTo(2);
        assertThat(counterCount(
                MicrometerTransactionProcessingMetricsRecorder
                        .IDEMPOTENCY_CONFLICTS,
                "result", "conflict"
        ) - idempotencyConflictBefore).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transaction",
                Integer.class
        )).isEqualTo(1);
        verify(externalRiskMockAdapter, times(1)).lookup(any());
        verify(httpClient, times(1)).analyzeV2(any(), anyString());
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            String key,
            TransactionCreateRequest request,
            String traceId
    ) throws Exception {
        return mockMvc.perform(post(PATH)
                .header("Idempotency-Key", key)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)));
    }

    private TransactionCreateRequest request(UUID transactionId) {
        return new TransactionCreateRequest(
                transactionId.toString(),
                "ACCOUNT_TRANSFER",
                "10000000",
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS).toString(),
                "customer_ref_metrics",
                "sender_ref_metrics",
                "recipient_ref_metrics",
                "MOBILE_BANKING",
                "device_ref_metrics"
        );
    }

    private TransactionCreateRequest withAmount(
            TransactionCreateRequest request,
            String amount
    ) {
        return new TransactionCreateRequest(
                request.transactionId(),
                request.transactionType(),
                amount,
                request.currencyCode(),
                request.occurredAt(),
                request.externalCustomerRef(),
                request.senderAccountRef(),
                request.recipientAccountRef(),
                request.channel(),
                request.deviceRef()
        );
    }

    private void stubSuccessfulRule(RuleVersion version) {
        when(httpClient.analyzeV2(any(), anyString())).thenAnswer(
                invocation -> response(
                        invocation.getArgument(0),
                        version,
                        invocation.getArgument(1)
                )
        );
    }

    private double counterCount(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private long timerCount(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }

    private String transactionStatus(String transactionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT processing_status
                        FROM financial_transaction
                        WHERE transaction_id = ?
                        """,
                String.class,
                UUID.fromString(transactionId)
        );
    }

    private String idempotencyStatus(String key) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT processing_status
                        FROM idempotency_record
                        WHERE idempotency_key = ?
                        """,
                String.class,
                key
        );
    }

    private String idempotencyFailureCode(String key) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT failure_code
                        FROM idempotency_record
                        WHERE idempotency_key = ?
                        """,
                String.class,
                key
        );
    }

    private long idempotencyRecordId(String key) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT id
                        FROM idempotency_record
                        WHERE idempotency_key = ?
                        """,
                Long.class,
                key
        );
    }

    private RuleAnalysisResponse response(
            RuleAnalysisRequestV2 request,
            RuleVersion version,
            String traceId
    ) {
        return new RuleAnalysisResponse(
                request.transaction().transactionId(),
                traceId,
                new RuleAnalysisResultResponse(
                        request.evaluationCutoffAt(),
                        "a".repeat(64),
                        new RuleScoringResultResponse(
                                "scoring-policy-v1",
                                version.getWeight(),
                                RuleRiskLevel.LOW,
                                List.of(),
                                List.of()
                        ),
                        List.of(new RuleEvidenceResponse(
                                RuleId.R001,
                                version.getRuleVersionId(),
                                RuleV1ContractRegistry
                                        .TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                                Integer.toString(version.getVersionNumber()),
                                version.getReasonCode(),
                                1,
                                0,
                                objectMapper.createObjectNode()
                                        .put("observedAmount", "10000000")
                                        .put("amountThreshold", "10000000"),
                                request.evaluationCutoffAt()
                        ))
                )
        );
    }

    private RuleVersion publishAmountRule() {
        RuleVersion version = amountRule();
        if (version.getStatus() != RuleVersionStatus.PUBLISHED) {
            ruleVersionLifecycleService.updateDraft(
                    version.getRuleVersionId(),
                    version.getReasonCode(),
                    version.getWeight(),
                    version.getConditionDefinition(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null
            );
            ruleVersionLifecycleService.publish(
                    version.getRuleVersionId(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS)
            );
        }
        return amountRule();
    }

    private RuleVersion amountRule() {
        return ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        1
                ).orElseThrow();
    }

    private void assertCounter(
            String name,
            long expected,
            String... tags
    ) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        assertThat(counter).as(name).isNotNull();
        assertThat(counter.count()).isEqualTo(expected);
    }

    private void assertTimer(
            String name,
            long expected,
            String... tags
    ) {
        Timer timer = meterRegistry.find(name).tags(tags).timer();
        assertThat(timer).as(name).isNotNull();
        assertThat(timer.count()).isEqualTo(expected);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .isGreaterThanOrEqualTo(0);
    }

    private void assertOnlyApprovedLowCardinalityTags() {
        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("finguardops.")) {
                continue;
            }
            assertThat(meter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .allMatch(Set.of(
                            "service", "result", "status", "riskLevel",
                            "failureCategory"
                    )::contains)
                    .doesNotContain(
                            "transactionId", "idempotencyKey", "fingerprint",
                            "traceId", "reference", "url", "path", "payload",
                            "credential", "exception", "message", "provider",
                            "deploymentVersion"
                    );
        }
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
