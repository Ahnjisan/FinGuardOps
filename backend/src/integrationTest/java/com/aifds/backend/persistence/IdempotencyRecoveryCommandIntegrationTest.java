package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.repository.AuditLogRepository;
import com.aifds.backend.audit.repository.JpaAuditLogRepository;
import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.externalrisk.port.ExternalRiskLookupPort;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.service.FraudCasePersistenceService;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditLog;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.repository.IdempotencyRecoveryAuditLogRepository;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.RiskResponseFinalizationService;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionSynchronousProcessingCoordinator;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandArguments;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandConfiguration;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandLauncher;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyRecoveryCommandIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-28T00:00:00Z");

    @Autowired
    private ApplicationContext normalContext;
    @Autowired
    private Environment environment;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TransactionRequestFingerprint requestFingerprint;
    @Autowired
    private TransactionIntakeService transactionIntakeService;

    @MockitoSpyBean
    private RiskResponseFinalizationService finalizationService;

    @MockitoBean
    private TransactionSynchronousProcessingCoordinator coordinator;

    @Test
    void normalStartupLoadsNoRecoveryCommandAndInvalidInputStartsNoContext()
            throws Exception {
        assertThat(normalContext.getBeansOfType(
                IdempotencyRecoveryCommandRunner.class
        )).isEmpty();
        assertThat(normalContext.getBeansOfType(
                IdempotencyRecoveryCommandConfiguration.class
        )).isEmpty();
        int writesBefore = totalWriteRows();
        AtomicInteger contextStarts = new AtomicInteger();
        CommandExecution execution = execute(
                new String[]{
                        option("enabled=true"),
                        option("action=recover"),
                        option("record-id=01")
                },
                () -> {
                    contextStarts.incrementAndGet();
                    return startLimitedContext();
                }
        );

        assertThat(execution.exitCode()).isEqualTo(2);
        assertThat(execution.standardOutput()).isEmpty();
        assertThat(execution.standardError())
                .contains("INVALID_RECOVERY_COMMAND")
                .doesNotContain("record-id", "01");
        assertThat(contextStarts).hasValue(0);
        assertThat(totalWriteRows()).isEqualTo(writesBefore);
    }

    @Test
    void limitedNonWebContextExcludesProcessingBeansAndInspectIsBoundedReadOnly()
            throws Exception {
        long first = insertCandidate(Instant.now().minusSeconds(7_200));
        insertCandidate(Instant.now().minusSeconds(7_100));
        int writesBefore = totalWriteRows();
        AtomicReference<ConfigurableApplicationContext> started =
                new AtomicReference<>();

        CommandExecution execution = execute(
                inspectArguments("PT30M", "1"),
                () -> {
                    ConfigurableApplicationContext context =
                            startLimitedContext();
                    started.set(context);
                    assertThat(context.getBeansOfType(
                            ExternalRiskLookupPort.class
                    )).isEmpty();
                    assertThat(context.getBeansOfType(
                            ExternalRiskRuleAnalysisCoordinator.class
                    )).isEmpty();
                    assertThat(context.getBeansOfType(
                            RuleAnalysisOrchestrationService.class
                    )).isEmpty();
                    assertThat(context.getBeansOfType(
                            RiskResponseFinalizationService.class
                    )).isEmpty();
                    assertThat(context.getBeansOfType(
                            TransactionSynchronousProcessingCoordinator.class
                    )).isEmpty();
                    assertThat(context.getBeansOfType(
                            FraudCasePersistenceService.class
                    )).isEmpty();
                    assertLimitedPersistenceClosure(context);
                    assertThat(context.getEnvironment().getProperty(
                            "local.server.port"
                    )).isNull();
                    return context;
                }
        );

        assertThat(execution.exitCode()).isZero();
        List<String> lines = execution.standardOutput().lines().toList();
        assertThat(lines).hasSize(2);
        JsonNode candidate = objectMapper.readTree(lines.get(0));
        assertThat(candidate.get("type").textValue())
                .isEqualTo("candidate");
        assertThat(candidate.get("recordId").longValue()).isEqualTo(first);
        JsonNode summary = objectMapper.readTree(lines.get(1));
        assertThat(summary.get("processedCount").intValue()).isEqualTo(1);
        assertThat(execution.standardError()).isEmpty();
        assertThat(totalWriteRows()).isEqualTo(writesBefore);
        assertThat(started.get()).isNotNull();
        assertThat(started.get().isActive()).isFalse();
    }

    private void assertLimitedPersistenceClosure(
            ConfigurableApplicationContext context
    ) {
        EntityManagerFactory entityManagerFactory = context.getBean(
                EntityManagerFactory.class
        );
        Set<Class<?>> entityTypes = entityManagerFactory.getMetamodel()
                .getEntities()
                .stream()
                .map(type -> type.getJavaType())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(entityTypes).containsExactlyInAnyOrder(
                AuditLog.class,
                DetectionEvidence.class,
                DetectionResult.class,
                CaseTransaction.class,
                FraudCase.class,
                IdempotencyRecord.class,
                IdempotencyRecoveryAuditLog.class,
                FraudRule.class,
                RuleVersion.class,
                FinancialTransaction.class
        ).doesNotContain(BehaviorEvent.class);

        assertThat(context.getBeansOfType(IdempotencyRecordRepository.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                IdempotencyRecoveryAuditLogRepository.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                FinancialTransactionRepository.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                DetectionEvidenceRepository.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(CaseTransactionRepository.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(AuditLogRepository.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(JpaAuditLogRepository.class))
                .hasSize(1);

        assertThat(context.getBeansOfType(BehaviorEventRepository.class))
                .isEmpty();
        assertThat(context.getBeansOfType(FraudRuleRepository.class))
                .isEmpty();
        assertThat(context.getBeansOfType(RuleVersionRepository.class))
                .isEmpty();
        assertThat(context.getBeansOfType(DetectionResultRepository.class))
                .isEmpty();
        assertThat(context.getBeansOfType(FraudCaseRepository.class))
                .isEmpty();
    }

    @Test
    void emptyInspectReturnsZeroAndWritesOnlySummary() throws Exception {
        CommandExecution execution = execute(
                inspectArguments("P7D", "100"),
                this::startLimitedContext
        );

        assertThat(execution.exitCode()).isZero();
        List<String> lines = execution.standardOutput().lines().toList();
        assertThat(lines).hasSize(1);
        JsonNode summary = objectMapper.readTree(lines.get(0));
        assertThat(summary.get("type").textValue()).isEqualTo("summary");
        assertThat(summary.get("processedCount").intValue()).isZero();
        assertThat(totalWriteRows()).isZero();
    }

    @Test
    void recoverCompletesOneRecordReplaysPubliclyAndRepeatsAsRejected()
            throws Exception {
        when(coordinator.isAvailable()).thenReturn(true);
        TransactionFingerprintInput input = fingerprintInput(UUID.randomUUID());
        String key = "command-replay-" + UUID.randomUUID();
        RecoveryFixture fixture = finalizedFixture(
                RiskLevel.LOW,
                input,
                key,
                requestFingerprint.calculate(input)
        );
        clearInvocations(finalizationService, coordinator);

        CommandExecution recovered = execute(
                recoverArguments(fixture.recordId()),
                this::startLimitedContext
        );

        assertThat(recovered.exitCode()).isZero();
        JsonNode result = objectMapper.readTree(
                recovered.standardOutput().strip()
        );
        assertThat(result.get("decision").textValue())
                .isEqualTo("RECOVERABLE_COMPLETION_GAP");
        assertThat(result.get("auditResult").textValue())
                .isEqualTo("RECOVERED");
        Map<String, Object> state = idempotencyState(fixture.recordId());
        assertThat(state)
                .containsEntry("processing_status", "COMPLETED");
        assertThat(state.get("response_snapshot")).isNotNull();
        assertThat(state.get("finished_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT response_snapshot->>'responseSchemaVersion' "
                        + "FROM idempotency_record WHERE id = ?",
                String.class,
                fixture.recordId()
        )).isEqualTo("transaction-create-response-v2");
        assertThat(recoveryAudits(fixture.recordId()))
                .containsExactly("RECOVERABLE_COMPLETION_GAP:RECOVERED");

        TransactionIntakeResult replay = transactionIntakeService.receive(
                key,
                request(input),
                "trace_command_replay_01"
        );
        assertThat(replay).isInstanceOf(
                TransactionIntakeResult.CompletedReplay.class
        );
        verify(coordinator).isAvailable();
        verify(coordinator, never()).process(anyLong(), any(), anyString());
        verify(finalizationService, never()).finalizeRiskResponse(any());

        CommandExecution repeated = execute(
                recoverArguments(fixture.recordId()),
                this::startLimitedContext
        );
        assertThat(repeated.exitCode()).isEqualTo(3);
        assertThat(objectMapper.readTree(repeated.standardOutput().strip())
                .get("decision").textValue())
                .isEqualTo("ALREADY_TERMINAL");
        assertThat(recoveryAudits(fixture.recordId())).containsExactly(
                "RECOVERABLE_COMPLETION_GAP:RECOVERED",
                "ALREADY_TERMINAL:REJECTED"
        );
    }

    @Test
    void typedRejectionLeavesBusinessAndIdempotencyStateUnchanged()
            throws Exception {
        RecoveryFixture fixture = receivedFixture();
        Map<String, Object> before = idempotencyState(fixture.recordId());
        int businessRowsBefore = businessRows();

        CommandExecution execution = execute(
                recoverArguments(fixture.recordId()),
                this::startLimitedContext
        );

        assertThat(execution.exitCode()).isEqualTo(3);
        JsonNode result = objectMapper.readTree(
                execution.standardOutput().strip()
        );
        assertThat(result.get("decision").textValue())
                .isEqualTo("PROCESSING_INDETERMINATE");
        assertThat(result.get("auditResult").textValue())
                .isEqualTo("REJECTED");
        assertThat(idempotencyState(fixture.recordId())).isEqualTo(before);
        assertThat(businessRows()).isEqualTo(businessRowsBefore);
        assertThat(recoveryAudits(fixture.recordId()))
                .containsExactly("PROCESSING_INDETERMINATE:REJECTED");
    }

    @Test
    void internalFailureRollsBackAndWritesFailedAuditWithoutLeakingDetails()
            throws Exception {
        RecoveryFixture fixture = finalizedFixture(
                RiskLevel.MEDIUM,
                fingerprintInput(UUID.randomUUID()),
                "command-failure-" + UUID.randomUUID(),
                "f".repeat(64)
        );
        installRecoveredAuditRejectionTrigger();
        CommandExecution execution;
        try {
            execution = execute(
                    recoverArguments(fixture.recordId()),
                    this::startLimitedContext
            );
        } finally {
            removeRecoveredAuditRejectionTrigger();
        }

        assertThat(execution.exitCode()).isEqualTo(1);
        assertThat(execution.standardOutput()).isEmpty();
        assertThat(execution.standardError())
                .contains("RECOVERY_INTERNAL_FAILURE")
                .doesNotContain(
                        "recovery audit rejected",
                        "org.postgresql",
                        "stack"
                );
        assertThat(idempotencyState(fixture.recordId()))
                .containsEntry("processing_status", "IN_PROGRESS")
                .containsEntry("response_snapshot", null)
                .containsEntry("finished_at", null);
        assertThat(recoveryAudits(fixture.recordId()))
                .containsExactly("INTERNAL_FAILURE:FAILED");
    }

    @Test
    void concurrentCommandsHaveOneWinnerAndMigrationsRemainV1ThroughV11()
            throws Exception {
        RecoveryFixture fixture = finalizedFixture(
                RiskLevel.LOW,
                fingerprintInput(UUID.randomUUID()),
                "command-concurrent-" + UUID.randomUUID(),
                "c".repeat(64)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<CommandExecution> command = () -> execute(
                    recoverArguments(fixture.recordId()),
                    this::startLimitedContext
            );
            Future<CommandExecution> first = executor.submit(command);
            Future<CommandExecution> second = executor.submit(command);
            assertThat(List.of(
                    first.get(60, TimeUnit.SECONDS).exitCode(),
                    second.get(60, TimeUnit.SECONDS).exitCode()
            )).containsExactlyInAnyOrder(0, 3);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(recoveryAudits(fixture.recordId())).containsExactlyInAnyOrder(
                "RECOVERABLE_COMPLETION_GAP:RECOVERED",
                "ALREADY_TERMINAL:REJECTED"
        );
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = TRUE AND type = 'SQL' "
                        + "ORDER BY installed_rank",
                String.class
        )).containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                "11"
        );
    }

    private ConfigurableApplicationContext startLimitedContext() {
        Map<String, Object> testProperties = Map.of(
                "spring.datasource.url",
                environment.getRequiredProperty("spring.datasource.url"),
                "spring.datasource.username",
                environment.getRequiredProperty("spring.datasource.username"),
                "spring.datasource.password",
                environment.getRequiredProperty("spring.datasource.password")
        );
        SpringApplication application = new SpringApplication(
                IdempotencyRecoveryCommandConfiguration.class
        );
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setAddCommandLineProperties(false);
        application.addInitializers(context -> context.getEnvironment()
                .getPropertySources().addFirst(new MapPropertySource(
                        "recoveryCommandIntegrationTest",
                        testProperties
                )));
        application.setDefaultProperties(Map.of(
                "logging.level.root",
                "OFF"
        ));
        return application.run();
    }

    private CommandExecution execute(
            String[] arguments,
            IdempotencyRecoveryCommandLauncher.RecoveryContextFactory factory
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = new IdempotencyRecoveryCommandLauncher(
                factory,
                output,
                error
        ).launch(arguments);
        return new CommandExecution(
                exitCode,
                output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8)
        );
    }

    private String[] inspectArguments(String threshold, String pageSize) {
        return new String[]{
                option("enabled=true"),
                option("action=inspect"),
                option("threshold=" + threshold),
                option("page-size=" + pageSize)
        };
    }

    private String[] recoverArguments(long recordId) {
        return new String[]{
                option("enabled=true"),
                option("action=recover"),
                option("record-id=" + recordId)
        };
    }

    private String option(String option) {
        return IdempotencyRecoveryCommandArguments.PREFIX + option;
    }

    private long insertCandidate(Instant updatedAt) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO idempotency_record (
                            operation_scope, idempotency_key,
                            request_fingerprint, processing_status,
                            expires_at, created_at, updated_at
                        ) VALUES (
                            ?, ?, ?, 'IN_PROGRESS',
                            CAST(? AS TIMESTAMPTZ) + INTERVAL '24 hours', ?, ?
                        )
                        RETURNING id
                        """,
                Long.class,
                OPERATION_SCOPE,
                "command-candidate-" + UUID.randomUUID(),
                "d".repeat(64),
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt)
        );
    }

    private RecoveryFixture finalizedFixture(
            RiskLevel riskLevel,
            TransactionFingerprintInput input,
            String key,
            String fingerprint
    ) {
        RecoveryFixture fixture = analyzedFixture(
                riskLevel,
                input,
                key,
                fingerprint
        );
        finalizationService.finalizeRiskResponse(fixture.transactionId());
        return fixture;
    }

    private RecoveryFixture analyzedFixture(
            RiskLevel riskLevel,
            TransactionFingerprintInput input,
            String key,
            String fingerprint
    ) {
        long transactionPk = insertTransaction(
                input.transactionId(),
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
                "UPDATE financial_transaction "
                        + "SET processing_status = 'ANALYZED', "
                        + "adopted_detection_result_id = ?, risk_level = ? "
                        + "WHERE id = ?",
                detectionPk,
                riskLevel.name(),
                transactionPk
        );
        long recordId = insertIdempotency(key, fingerprint, transactionPk);
        return new RecoveryFixture(
                recordId,
                transactionPk,
                input.transactionId(),
                detectionResultId
        );
    }

    private RecoveryFixture receivedFixture() {
        TransactionFingerprintInput input = fingerprintInput(UUID.randomUUID());
        long transactionPk = insertTransaction(
                input.transactionId(),
                TransactionProcessingStatus.RECEIVED,
                input
        );
        long recordId = insertIdempotency(
                "command-received-" + UUID.randomUUID(),
                "b".repeat(64),
                transactionPk
        );
        return new RecoveryFixture(
                recordId,
                transactionPk,
                input.transactionId(),
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
                            transaction_id, transaction_type, amount,
                            currency_code, occurred_at, external_customer_ref,
                            sender_account_ref, recipient_account_ref,
                            channel, device_ref, processing_status
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
                            detection_result_id, financial_transaction_id,
                            detection_result_version, analysis_status,
                            risk_score, risk_level, rule_set_version,
                            scoring_policy_version, feature_version,
                            evaluation_cutoff_at, analysis_started_at,
                            analysis_completed_at, analysis_trace_id
                        ) VALUES (
                            ?, ?, 1, 'COMPLETED', 90, ?, 'rule-set-v1',
                            'scoring-v1', 'feature-v1', ?, ?, ?,
                            'trace_command_recovery_01'
                        ) RETURNING id
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
                            operation_scope, idempotency_key,
                            request_fingerprint, processing_status,
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

    private TransactionFingerprintInput fingerprintInput(UUID transactionId) {
        return new TransactionFingerprintInput(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(125_000),
                "KRW",
                OCCURRED_AT,
                "customer_ref_command",
                "sender_ref_command",
                "recipient_ref_command",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_command"
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

    private Map<String, Object> idempotencyState(long recordId) {
        return jdbcTemplate.queryForMap(
                "SELECT processing_status, response_snapshot::text AS "
                        + "response_snapshot, finished_at "
                        + "FROM idempotency_record WHERE id = ?",
                recordId
        );
    }

    private List<String> recoveryAudits(long recordId) {
        return jdbcTemplate.queryForList(
                "SELECT recovery_decision || ':' || audit_result "
                        + "FROM idempotency_recovery_audit_log "
                        + "WHERE idempotency_record_id = ? ORDER BY id",
                String.class,
                recordId
        );
    }

    private int totalWriteRows() {
        return jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM idempotency_record) + "
                        + "(SELECT COUNT(*) FROM idempotency_recovery_audit_log)",
                Integer.class
        );
    }

    private int businessRows() {
        return jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM financial_transaction) + "
                        + "(SELECT COUNT(*) FROM detection_result) + "
                        + "(SELECT COUNT(*) FROM fraud_case) + "
                        + "(SELECT COUNT(*) FROM case_transaction) + "
                        + "(SELECT COUNT(*) FROM audit_log)",
                Integer.class
        );
    }

    private void installRecoveredAuditRejectionTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_command_recovery_audit_for_test()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'recovery audit rejected for test';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_command_recovery_audit_for_test
                BEFORE INSERT ON idempotency_recovery_audit_log
                FOR EACH ROW WHEN (NEW.audit_result = 'RECOVERED')
                EXECUTE FUNCTION reject_command_recovery_audit_for_test()
                """);
    }

    private void removeRecoveredAuditRejectionTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS reject_command_recovery_audit_for_test
                ON idempotency_recovery_audit_log
                """);
        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS reject_command_recovery_audit_for_test()
                """);
    }

    private record CommandExecution(
            int exitCode,
            String standardOutput,
            String standardError
    ) {
    }

    private record RecoveryFixture(
            long recordId,
            long transactionPk,
            UUID transactionId,
            UUID detectionResultId
    ) {
    }
}
