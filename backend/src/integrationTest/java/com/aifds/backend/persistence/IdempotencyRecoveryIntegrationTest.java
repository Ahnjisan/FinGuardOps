package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditLog;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditResult;
import com.aifds.backend.idempotency.repository.IdempotencyRecoveryAuditLogRepository;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryCandidate;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import com.aifds.backend.transaction.service.TransactionFinalResponseSnapshot;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionIntakeSnapshotCodec;
import com.aifds.backend.transaction.service.TransactionIntakeSnapshotReplay;
import com.aifds.backend.transaction.service.TransactionSynchronousProcessingCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyRecoveryIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";
    private static final Instant DATABASE_NOW =
            Instant.parse("2026-08-28T06:00:00.123456Z");
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-28T00:00:00Z");

    @Autowired
    private IdempotencyRecoveryService recoveryService;
    @MockitoSpyBean
    private RiskResponseFinalizationService finalizationService;
    @Autowired
    private TransactionIntakeService transactionIntakeService;
    @Autowired
    private IdempotencyRecoveryAuditLogRepository recoveryAuditRepository;
    @Autowired
    private TransactionRequestFingerprint requestFingerprint;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private DatabaseTransactionTimestampProvider timestampProvider;

    @MockitoBean
    private TransactionSynchronousProcessingCoordinator coordinator;

    @MockitoSpyBean
    private TransactionIntakeSnapshotCodec snapshotCodec;

    @Test
    void findsBoundedCandidatesAtInclusiveCutoffWithStableOrderAndScope() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        Instant cutoff = DATABASE_NOW.minus(Duration.ofMinutes(30));
        long first = insertCandidate(
                OPERATION_SCOPE,
                cutoff.minusSeconds(1),
                cutoff.minus(Duration.ofHours(25))
        );
        long second = insertCandidate(
                OPERATION_SCOPE,
                cutoff,
                cutoff.minusSeconds(10)
        );
        long third = insertCandidate(
                OPERATION_SCOPE,
                cutoff,
                cutoff.minusSeconds(10)
        );
        insertCandidate(
                "POST:/api/v1/other",
                cutoff.minusSeconds(2),
                cutoff.minusSeconds(10)
        );
        insertCandidate(
                OPERATION_SCOPE,
                cutoff.plusNanos(1_000),
                cutoff.minusSeconds(10)
        );

        List<IdempotencyRecoveryCandidate> candidates = recoveryService
                .findLongRunningCandidates(Duration.ofMinutes(30), 100);

        assertThat(candidates)
                .extracting(IdempotencyRecoveryCandidate::idempotencyRecordId)
                .containsExactly(first, second, third);
        assertThat(candidates)
                .allMatch(candidate -> candidate.transactionId() == null)
                .extracting(IdempotencyRecoveryCandidate::updatedAt)
                .containsExactly(
                        cutoff.minusSeconds(1),
                        cutoff,
                        cutoff
                );
    }

    @Test
    void appliesLimitsOneFiftyAndOneHundredWithoutCountOrOffsetContract() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        Instant updatedAt = DATABASE_NOW.minus(Duration.ofHours(1));
        IntStream.range(0, 105).forEach(index -> insertCandidate(
                OPERATION_SCOPE,
                updatedAt.plusNanos(index * 1_000L),
                updatedAt.minusSeconds(1)
        ));

        assertThat(recoveryService.findLongRunningCandidates(
                Duration.ofMinutes(30),
                1
        )).hasSize(1);
        assertThat(recoveryService.findLongRunningCandidates(
                Duration.ofMinutes(30),
                50
        )).hasSize(50);
        assertThat(recoveryService.findLongRunningCandidates(
                Duration.ofMinutes(30),
                100
        )).hasSize(100);
    }

    @Test
    void migrationCreatesApprovedPartialCandidateIndex() {
        String definition = jdbcTemplate.queryForObject(
                """
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND indexname =
                              'ix_idempotency_record_recovery_candidates'
                        """,
                String.class
        );

        assertThat(definition)
                .contains("(operation_scope, updated_at, id)")
                .contains("processing_status")
                .contains("'IN_PROGRESS'::text");
    }

    @Test
    void appendOnlyRepositoryInsertsAndRejectsDuplicateAuditId() {
        IdempotencyRecoveryAuditLog original = safeRejectedAudit(901L);
        TransactionTemplate transactions = new TransactionTemplate(
                transactionManager
        );

        transactions.executeWithoutResult(status ->
                recoveryAuditRepository.insert(original)
        );

        assertThat(original.getId()).isNotNull();
        assertThat(recoveryAuditRows(901L)).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.get("recovery_decision"))
                            .isEqualTo("MISSING_IDEMPOTENCY_RECORD");
                    assertThat(audit.get("audit_result"))
                            .isEqualTo("REJECTED");
                });

        IdempotencyRecoveryAuditLog duplicate = safeRejectedAudit(902L);
        ReflectionTestUtils.setField(
                duplicate,
                "auditId",
                original.getAuditId()
        );
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                recoveryAuditRepository.insert(duplicate)
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_recovery_audit_log",
                Integer.class
        )).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("approvedRiskCombinations")
    void recoversEveryApprovedRiskCombinationWithOneTimestamp(
            RiskLevel riskLevel,
            TransactionProcessingStatus expectedStatus,
            RiskResponseOutcome expectedOutcome,
            boolean caseRequired
    ) throws Exception {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        RecoveryFixture fixture = finalizedFixture(riskLevel);

        IdempotencyRecoveryResult result = recoveryService.recover(
                fixture.recordId(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );

        assertThat(result.decision()).isEqualTo(
                IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP
        );
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                """
                        SELECT processing_status,
                               response_snapshot::text AS response_snapshot,
                               finished_at
                        FROM idempotency_record
                        WHERE id = ?
                        """,
                fixture.recordId()
        );
        JsonNode snapshot = objectMapper.readTree(
                (String) stored.get("response_snapshot")
        );
        assertThat(stored.get("processing_status")).isEqualTo("COMPLETED");
        assertThat(((Timestamp) stored.get("finished_at")).toInstant())
                .isEqualTo(DATABASE_NOW);
        assertThat(Instant.parse(snapshot.get("finalizedAt").textValue()))
                .isEqualTo(DATABASE_NOW);
        assertThat(snapshot.get("httpStatus").intValue()).isEqualTo(201);
        assertThat(snapshot.get("responseSchemaVersion").textValue())
                .isEqualTo("transaction-create-response-v2");
        assertThat(snapshot.get("codecVersion").textValue())
                .isEqualTo("transaction-intake-snapshot-envelope-v2");
        JsonNode body = snapshot.get("responseBody");
        assertThat(body.get("processingStatus").textValue())
                .isEqualTo(expectedStatus.name());
        assertThat(body.get("riskResponseOutcome").textValue())
                .isEqualTo(expectedOutcome.name());
        assertThat(body.get("riskLevel").textValue())
                .isEqualTo(riskLevel.name());
        assertThat(body.get("caseId").isNull()).isEqualTo(!caseRequired);
        assertThat(recoveryAuditRows(fixture.recordId())).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.get("recovery_decision"))
                            .isEqualTo("RECOVERABLE_COMPLETION_GAP");
                    assertThat(audit.get("audit_result"))
                            .isEqualTo("RECOVERED");
                    assertThat(((Timestamp) audit.get("attempted_at"))
                            .toInstant()).isEqualTo(DATABASE_NOW);
                });
    }

    @Test
    void rejectsMissingReceivedAnalyzedFailedCaseAndAuditStatesWithoutMutation() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);

        assertRejected(
                99_999L,
                IdempotencyRecoveryDecision.MISSING_IDEMPOTENCY_RECORD
        );
        long missingTransaction = insertCandidate(
                OPERATION_SCOPE,
                DATABASE_NOW.minus(Duration.ofHours(1)),
                DATABASE_NOW.minus(Duration.ofHours(1))
        );
        assertRejected(
                missingTransaction,
                IdempotencyRecoveryDecision.MISSING_TRANSACTION
        );

        RecoveryFixture received = receivedFixture();
        assertRejected(
                received.recordId(),
                IdempotencyRecoveryDecision.PROCESSING_INDETERMINATE
        );

        RecoveryFixture analyzed = analyzedFixture(RiskLevel.LOW);
        assertRejected(
                analyzed.recordId(),
                IdempotencyRecoveryDecision.FINALIZATION_INCOMPLETE
        );

        RecoveryFixture failed = failedFixture();
        assertRejected(
                failed.recordId(),
                IdempotencyRecoveryDecision.CONFIRMED_DOMAIN_FAILURE
        );

        RecoveryFixture highWithoutCase = analyzedFixture(RiskLevel.HIGH);
        finalizationWithoutCase(highWithoutCase, RiskLevel.HIGH);
        assertRejected(
                highWithoutCase.recordId(),
                IdempotencyRecoveryDecision
                        .INCONSISTENT_CASE_RELATIONSHIP
        );

        RecoveryFixture noAudits = analyzedFixture(RiskLevel.LOW);
        finalizationWithoutCase(noAudits, RiskLevel.LOW);
        assertRejected(
                noAudits.recordId(),
                IdempotencyRecoveryDecision.FINALIZATION_AUDIT_MISMATCH
        );
    }

    @Test
    void rejectsAlreadyTerminalRecordAndLeavesSnapshotUnchanged() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        RecoveryFixture fixture = finalizedFixture(RiskLevel.LOW);
        recoveryService.recover(
                fixture.recordId(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        String originalSnapshot = jdbcTemplate.queryForObject(
                "SELECT response_snapshot::text FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        );

        IdempotencyRecoveryResult loser = recoveryService.recover(
                fixture.recordId(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );

        assertThat(loser.decision())
                .isEqualTo(IdempotencyRecoveryDecision.ALREADY_TERMINAL);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT response_snapshot::text FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        )).isEqualTo(originalSnapshot);
        assertThat(recoveryAuditRows(fixture.recordId()))
                .extracting(row -> row.get("audit_result"))
                .containsExactly("RECOVERED", "REJECTED");
    }

    @Test
    void rollsBackCompletionWhenSuccessAuditInsertFails() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        RecoveryFixture fixture = finalizedFixture(RiskLevel.MEDIUM);
        installRecoveryAuditRejectionTrigger(
                "NEW.audit_result = 'RECOVERED'"
        );
        try {
            assertThatThrownBy(() -> recoveryService.recover(
                    fixture.recordId(),
                    AuditActorType.SYSTEM,
                    AuditLog.SYSTEM_ACTOR_ID
            )).isInstanceOf(RuntimeException.class);
        } finally {
            removeRecoveryAuditRejectionTrigger();
        }

        assertThat(idempotencyState(fixture.recordId()))
                .containsEntry("processing_status", "IN_PROGRESS")
                .containsEntry("response_snapshot", null)
                .containsEntry("finished_at", null);
        assertThat(recoveryAuditRows(fixture.recordId())).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.get("recovery_decision"))
                            .isEqualTo("INTERNAL_FAILURE");
                    assertThat(audit.get("audit_result")).isEqualTo("FAILED");
                });
    }

    @Test
    void rollsBackCodecFailureThenWritesOneSafeInternalFailureAudit() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        RecoveryFixture fixture = finalizedFixture(RiskLevel.LOW);
        IllegalStateException original = new IllegalStateException(
                "codec low-level detail must not be persisted"
        );
        doThrow(original).when(snapshotCodec).encodeV2(
                any(TransactionFinalResponseSnapshot.class),
                eq(201),
                eq(DATABASE_NOW)
        );

        assertThatThrownBy(() -> recoveryService.recover(
                fixture.recordId(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).isSameAs(original);
        assertThat(idempotencyState(fixture.recordId()))
                .containsEntry("processing_status", "IN_PROGRESS")
                .containsEntry("response_snapshot", null)
                .containsEntry("finished_at", null);
        assertThat(recoveryAuditRows(fixture.recordId())).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.get("recovery_decision"))
                            .isEqualTo("INTERNAL_FAILURE");
                    assertThat(audit.get("audit_result")).isEqualTo("FAILED");
                    assertThat(audit.keySet())
                            .doesNotContain("exception", "snapshot");
                });
    }

    @Test
    void preservesOriginalFailureAndSuppressesFailureAuditError() {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        RecoveryFixture fixture = finalizedFixture(RiskLevel.LOW);
        IllegalStateException original = new IllegalStateException(
                "original codec failure"
        );
        doThrow(original).when(snapshotCodec).encodeV2(
                any(TransactionFinalResponseSnapshot.class),
                eq(201),
                eq(DATABASE_NOW)
        );
        installRecoveryAuditRejectionTrigger("TRUE");
        try {
            assertThatThrownBy(() -> recoveryService.recover(
                    fixture.recordId(),
                    AuditActorType.SYSTEM,
                    AuditLog.SYSTEM_ACTOR_ID
            )).isSameAs(original);
            assertThat(original.getSuppressed()).hasSize(1);
        } finally {
            removeRecoveryAuditRejectionTrigger();
        }
        assertThat(recoveryAuditRows(fixture.recordId())).isEmpty();
    }

    @Test
    void recoveredSnapshotIsExactReplayWithoutNewProcessing()
            throws Exception {
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(DATABASE_NOW);
        when(coordinator.isAvailable()).thenReturn(true);
        TransactionFingerprintInput input = fingerprintInput(UUID.randomUUID());
        String key = "recovery-public-replay-key";
        String fingerprint = requestFingerprint.calculate(input);
        RecoveryFixture fixture = finalizedFixture(
                RiskLevel.CRITICAL,
                input,
                key,
                fingerprint
        );
        clearInvocations(finalizationService, coordinator);
        recoveryService.recover(
                fixture.recordId(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        String storedSnapshot = jdbcTemplate.queryForObject(
                "SELECT response_snapshot::text FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        );
        TransactionIntakeSnapshotReplay decoded = snapshotCodec.decode(
                storedSnapshot
        );
        Map<String, Integer> beforeReplay = storedBusinessRowCounts();

        TransactionIntakeResult replay = transactionIntakeService.receive(
                key,
                request(input),
                "trace_recovery_public_replay_01"
        );

        assertThat(replay).isInstanceOf(
                TransactionIntakeResult.CompletedReplay.class
        );
        TransactionIntakeResult.CompletedReplay completed =
                (TransactionIntakeResult.CompletedReplay) replay;
        assertThat(completed.httpStatus()).isEqualTo(201);
        assertThat(completed.snapshot()).isEqualTo(decoded.snapshot());
        assertThat(completed.snapshot().transactionId())
                .isEqualTo(input.transactionId());
        assertThat(completed.snapshot().processingStatus())
                .isEqualTo(TransactionProcessingStatus.HELD);
        assertThat(completed.snapshot().riskLevel()).isEqualTo("CRITICAL");
        assertThat(completed.snapshot().riskResponseOutcome())
                .isEqualTo("HELD");
        assertThat(completed.snapshot().adoptedDetectionResultId())
                .isEqualTo(fixture.detectionResultId().toString());
        assertThat(completed.snapshot().caseId()).isEqualTo(
                jdbcTemplate.queryForObject(
                        """
                                SELECT fraud_case.case_id::text
                                FROM case_transaction
                                JOIN fraud_case ON fraud_case.id =
                                    case_transaction.fraud_case_id
                                WHERE case_transaction.financial_transaction_id = ?
                                """,
                        String.class,
                        fixture.transactionPk()
                )
        );
        assertThat(storedBusinessRowCounts()).isEqualTo(beforeReplay);
        assertThat(recoveryAuditRows(fixture.recordId())).hasSize(1);
        verify(coordinator).isAvailable();
        verify(coordinator, never()).process(
                anyLong(),
                any(),
                anyString()
        );
        verify(finalizationService, never()).finalizeRiskResponse(any());
    }

    private void assertRejected(
            long recordId,
            IdempotencyRecoveryDecision expectedDecision
    ) {
        Map<String, Object> before = recordExists(recordId)
                ? idempotencyState(recordId)
                : Map.of();
        IdempotencyRecoveryResult result = recoveryService.recover(
                recordId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        assertThat(result.decision()).isEqualTo(expectedDecision);
        if (recordExists(recordId)) {
            assertThat(idempotencyState(recordId)).isEqualTo(before);
        }
        assertThat(recoveryAuditRows(recordId)).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.get("recovery_decision"))
                            .isEqualTo(expectedDecision.name());
                    assertThat(audit.get("audit_result"))
                            .isEqualTo("REJECTED");
                });
    }

    private RecoveryFixture finalizedFixture(RiskLevel riskLevel) {
        RecoveryFixture fixture = analyzedFixture(riskLevel);
        finalizationService.finalizeRiskResponse(fixture.transactionId());
        return fixture;
    }

    private RecoveryFixture finalizedFixture(
            RiskLevel riskLevel,
            TransactionFingerprintInput input,
            String key,
            String fingerprint
    ) {
        RecoveryFixture fixture = analyzedFixture(
                riskLevel,
                input.transactionId(),
                input,
                key,
                fingerprint
        );
        finalizationService.finalizeRiskResponse(fixture.transactionId());
        return fixture;
    }

    private RecoveryFixture analyzedFixture(RiskLevel riskLevel) {
        UUID transactionId = UUID.randomUUID();
        return analyzedFixture(
                riskLevel,
                transactionId,
                fingerprintInput(transactionId),
                "recovery-key-" + UUID.randomUUID(),
                "a".repeat(64)
        );
    }

    private RecoveryFixture analyzedFixture(
            RiskLevel riskLevel,
            UUID transactionId,
            TransactionFingerprintInput input,
            String key,
            String fingerprint
    ) {
        long transactionPk = insertTransaction(
                transactionId,
                TransactionProcessingStatus.RECEIVED,
                input
        );
        UUID detectionResultId = UUID.randomUUID();
        long detectionPk = insertCompletedDetection(
                transactionPk,
                detectionResultId,
                riskLevel
        );
        jdbcTemplate.update(
                """
                        UPDATE financial_transaction
                        SET processing_status = 'ANALYZED',
                            adopted_detection_result_id = ?,
                            risk_level = ?
                        WHERE id = ?
                        """,
                detectionPk,
                riskLevel.name(),
                transactionPk
        );
        long recordId = insertIdempotency(
                key,
                fingerprint,
                transactionPk
        );
        return new RecoveryFixture(
                recordId,
                transactionPk,
                transactionId,
                detectionResultId
        );
    }

    private RecoveryFixture receivedFixture() {
        UUID transactionId = UUID.randomUUID();
        TransactionFingerprintInput input = fingerprintInput(transactionId);
        long transactionPk = insertTransaction(
                transactionId,
                TransactionProcessingStatus.RECEIVED,
                input
        );
        long recordId = insertIdempotency(
                "received-recovery-" + UUID.randomUUID(),
                "b".repeat(64),
                transactionPk
        );
        return new RecoveryFixture(
                recordId,
                transactionPk,
                transactionId,
                null
        );
    }

    private RecoveryFixture failedFixture() {
        UUID transactionId = UUID.randomUUID();
        TransactionFingerprintInput input = fingerprintInput(transactionId);
        long transactionPk = insertTransaction(
                transactionId,
                TransactionProcessingStatus.FAILED,
                input
        );
        long recordId = insertIdempotency(
                "failed-recovery-" + UUID.randomUUID(),
                "c".repeat(64),
                transactionPk
        );
        return new RecoveryFixture(
                recordId,
                transactionPk,
                transactionId,
                null
        );
    }

    private long insertTransaction(
            UUID transactionId,
            TransactionProcessingStatus status,
            TransactionFingerprintInput input
    ) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO financial_transaction (
                            transaction_id,
                            transaction_type,
                            amount,
                            currency_code,
                            occurred_at,
                            external_customer_ref,
                            sender_account_ref,
                            recipient_account_ref,
                            channel,
                            device_ref,
                            processing_status
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                transactionId,
                input.transactionType().name(),
                input.amount(),
                input.currencyCode(),
                Timestamp.from(input.occurredAt()),
                input.externalCustomerRef(),
                input.senderAccountRef(),
                input.recipientAccountRef(),
                input.channel().name(),
                input.deviceRef(),
                status.name()
        );
    }

    private long insertCompletedDetection(
            long transactionPk,
            UUID detectionResultId,
            RiskLevel riskLevel
    ) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO detection_result (
                            detection_result_id,
                            financial_transaction_id,
                            detection_result_version,
                            analysis_status,
                            risk_score,
                            risk_level,
                            rule_set_version,
                            scoring_policy_version,
                            feature_version,
                            evaluation_cutoff_at,
                            analysis_started_at,
                            analysis_completed_at,
                            analysis_trace_id
                        ) VALUES (
                            ?, ?, 1, 'COMPLETED', 90, ?,
                            'rule-set-v1', 'scoring-v1', 'feature-v1',
                            ?, ?, ?, 'trace_recovery_integration_01'
                        )
                        RETURNING id
                        """,
                Long.class,
                detectionResultId,
                transactionPk,
                riskLevel.name(),
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT.plusSeconds(1)),
                Timestamp.from(OCCURRED_AT.plusSeconds(2))
        );
    }

    private long insertIdempotency(
            String key,
            String fingerprint,
            long transactionPk
    ) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO idempotency_record (
                            operation_scope,
                            idempotency_key,
                            request_fingerprint,
                            processing_status,
                            financial_transaction_id
                        ) VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                        RETURNING id
                        """,
                Long.class,
                OPERATION_SCOPE,
                key,
                fingerprint,
                transactionPk
        );
    }

    private long insertCandidate(
            String operationScope,
            Instant updatedAt,
            Instant createdAt
    ) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO idempotency_record (
                            operation_scope,
                            idempotency_key,
                            request_fingerprint,
                            processing_status,
                            expires_at,
                            created_at,
                            updated_at
                        ) VALUES (
                            ?, ?, ?, 'IN_PROGRESS',
                            CAST(? AS TIMESTAMPTZ) + INTERVAL '24 hours', ?, ?
                        )
                        RETURNING id
                        """,
                Long.class,
                operationScope,
                "candidate-" + UUID.randomUUID(),
                "d".repeat(64),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(updatedAt)
        );
    }

    private void finalizationWithoutCase(
            RecoveryFixture fixture,
            RiskLevel riskLevel
    ) {
        TransactionProcessingStatus status = switch (riskLevel) {
            case LOW, MEDIUM -> TransactionProcessingStatus.APPROVED;
            case HIGH -> TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED;
            case CRITICAL -> TransactionProcessingStatus.HELD;
        };
        RiskResponseOutcome outcome = switch (riskLevel) {
            case LOW -> RiskResponseOutcome.APPROVED;
            case MEDIUM -> RiskResponseOutcome.APPROVED_WITH_MONITORING;
            case HIGH -> RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED;
            case CRITICAL -> RiskResponseOutcome.HELD;
        };
        jdbcTemplate.update(
                """
                        UPDATE financial_transaction
                        SET processing_status = ?,
                            risk_response_outcome = ?
                        WHERE id = ?
                        """,
                status.name(),
                outcome.name(),
                fixture.transactionPk()
        );
    }

    private TransactionFingerprintInput fingerprintInput(UUID transactionId) {
        return new TransactionFingerprintInput(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(125_000),
                "KRW",
                OCCURRED_AT,
                "customer_ref_recovery",
                "sender_ref_recovery",
                "recipient_ref_recovery",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_recovery"
        );
    }

    private TransactionCreateRequest request(TransactionFingerprintInput input) {
        return new TransactionCreateRequest(
                input.transactionId().toString(),
                input.transactionType().name(),
                input.amount().toPlainString(),
                input.currencyCode(),
                input.occurredAt().toString(),
                input.externalCustomerRef(),
                input.senderAccountRef(),
                input.recipientAccountRef(),
                input.channel().name(),
                input.deviceRef()
        );
    }

    private Map<String, Integer> storedBusinessRowCounts() {
        return Map.of(
                "transaction",
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM financial_transaction",
                        Integer.class
                ),
                "detection",
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM detection_result",
                        Integer.class
                ),
                "caseLink",
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM case_transaction",
                        Integer.class
                ),
                "businessAudit",
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log",
                        Integer.class
                ),
                "recoveryAudit",
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM idempotency_recovery_audit_log",
                        Integer.class
                )
        );
    }

    private IdempotencyRecoveryAuditLog safeRejectedAudit(long recordId) {
        return IdempotencyRecoveryAuditLog.create(
                recordId,
                null,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                IdempotencyRecoveryDecision.MISSING_IDEMPOTENCY_RECORD,
                IdempotencyRecoveryAuditResult.REJECTED,
                DATABASE_NOW
        );
    }

    private Map<String, Object> idempotencyState(long recordId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT processing_status,
                               response_snapshot::text AS response_snapshot,
                               failure_code,
                               finished_at
                        FROM idempotency_record
                        WHERE id = ?
                        """,
                recordId
        );
    }

    private boolean recordExists(long recordId) {
        return jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM idempotency_record WHERE id = ?)",
                Boolean.class,
                recordId
        );
    }

    private List<Map<String, Object>> recoveryAuditRows(long recordId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT recovery_decision, audit_result, attempted_at,
                               transaction_id, actor_type, actor_id
                        FROM idempotency_recovery_audit_log
                        WHERE idempotency_record_id = ?
                        ORDER BY id
                        """,
                recordId
        );
    }

    private void installRecoveryAuditRejectionTrigger(String predicate) {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_recovery_audit_for_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'recovery audit rejected for test';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_recovery_audit_for_test
                BEFORE INSERT ON idempotency_recovery_audit_log
                FOR EACH ROW
                WHEN (""" + predicate + ")\n"
                + "EXECUTE FUNCTION reject_recovery_audit_for_test()"
        );
    }

    private void removeRecoveryAuditRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS reject_recovery_audit_for_test
                ON idempotency_recovery_audit_log
                """);
        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS reject_recovery_audit_for_test()
                """);
    }

    private static Stream<Arguments> approvedRiskCombinations() {
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

    private record RecoveryFixture(
            long recordId,
            long transactionPk,
            UUID transactionId,
            UUID detectionResultId
    ) {
    }
}
