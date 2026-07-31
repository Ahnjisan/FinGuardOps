package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.entity.RuleEvidenceObservationSummary;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.detection.service.DetectionResultPersistenceService;
import com.aifds.backend.detection.service.RuleEvidenceDraft;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.service.RuleVersionLifecycleService;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DetectionPersistenceIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private DetectionResultRepository resultRepository;

    @Autowired
    private DetectionEvidenceRepository evidenceRepository;

    @Autowired
    private DetectionResultPersistenceService persistenceService;

    @Autowired
    private RuleVersionRepository ruleVersionRepository;

    @Autowired
    private RuleVersionLifecycleService ruleVersionLifecycleService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void migrationCreatesExactColumnsConstraintsIndexesAndTriggers() {
        assertThat(flyway.info().applied()).hasSize(5);
        assertThat(columns("detection_result")).containsExactlyInAnyOrder(
                "id",
                "detection_result_id",
                "financial_transaction_id",
                "detection_result_version",
                "analysis_status",
                "risk_score",
                "risk_level",
                "rule_set_version",
                "scoring_policy_version",
                "feature_version",
                "model_version",
                "evaluation_cutoff_at",
                "analysis_started_at",
                "analysis_completed_at",
                "failure_code",
                "analysis_trace_id",
                "created_at",
                "updated_at"
        );
        assertThat(columns("detection_evidence")).containsExactlyInAnyOrder(
                "id",
                "evidence_id",
                "detection_result_id",
                "evidence_type",
                "reason_code",
                "display_description",
                "score_contribution",
                "rule_code",
                "rule_version",
                "rule_version_id",
                "observation_summary",
                "evidence_occurred_at",
                "sort_order",
                "created_at"
        );
        assertThat(constraints("detection_result")).containsExactlyInAnyOrder(
                "pk_detection_result",
                "uq_detection_result_business_id",
                "uq_detection_result_transaction_version",
                "uq_detection_result_adoption_target",
                "fk_detection_result_transaction",
                "ck_detection_result_uuid_v4",
                "ck_detection_result_version",
                "ck_detection_result_analysis_status",
                "ck_detection_result_risk_score",
                "ck_detection_result_risk_level",
                "ck_detection_result_version_fields",
                "ck_detection_result_failure_code",
                "ck_detection_result_trace_id",
                "ck_detection_result_state_fields",
                "ck_detection_result_timestamps"
        );
        assertThat(constraints("detection_evidence"))
                .containsExactlyInAnyOrder(
                        "pk_detection_evidence",
                        "uq_detection_evidence_business_id",
                        "uq_detection_evidence_result_sort",
                        "fk_detection_evidence_result",
                        "fk_detection_evidence_rule_version",
                        "ck_detection_evidence_uuid_v4",
                        "ck_detection_evidence_type",
                        "ck_detection_evidence_reason_code",
                        "ck_detection_evidence_description",
                        "ck_detection_evidence_score",
                        "ck_detection_evidence_rule_fields",
                        "ck_detection_evidence_observation_summary",
                        "ck_detection_evidence_sort_order",
                        "ck_detection_evidence_rule_version_type"
                );
        assertThat(indexes("detection_evidence")).contains(
                "uq_detection_evidence_result_rule_code",
                "ix_detection_evidence_rule_version_id"
        );
        assertThat(triggers()).containsExactlyInAnyOrder(
                "tg_detection_result_history_guard",
                "tg_detection_evidence_history_guard",
                "tg_financial_transaction_adoption_guard"
        );
    }

    @Test
    void enforcesUuidBusinessIdsAndTransactionVersionUniqueness() {
        FinancialTransaction transaction = saveTransaction(UUID.randomUUID());
        UUID resultId = UUID.randomUUID();
        insertPendingResult(transaction.getId(), resultId, 1);

        assertConstraint(
                () -> insertPendingResult(
                        transaction.getId(),
                        UUID.nameUUIDFromBytes(new byte[]{1}),
                        2
                ),
                "ck_detection_result_uuid_v4"
        );
        assertConstraint(
                () -> insertPendingResult(
                        transaction.getId(),
                        resultId,
                        2
                ),
                "uq_detection_result_business_id"
        );
        assertConstraint(
                () -> insertPendingResult(
                        transaction.getId(),
                        UUID.randomUUID(),
                        1
                ),
                "uq_detection_result_transaction_version"
        );
    }

    @Test
    void enforcesStateFieldsRiskScoreAndFailedTerminalHistory() {
        FinancialTransaction transaction = saveTransaction(UUID.randomUUID());
        long failedId = insertPendingResult(
                transaction.getId(),
                UUID.randomUUID(),
                1
        );
        jdbcTemplate.update("""
                UPDATE detection_result
                SET analysis_status = 'FAILED',
                    failure_code = 'DEPENDENCY_TIMEOUT',
                    analysis_completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, failedId);

        assertTriggerViolation(() -> jdbcTemplate.update("""
                UPDATE detection_result
                SET failure_code = 'OTHER_FAILURE',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, failedId));
        assertTriggerViolation(() -> jdbcTemplate.update(
                "DELETE FROM detection_result WHERE id = ?",
                failedId
        ));
        assertConstraint(
                () -> jdbcTemplate.update("""
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
                            ?, ?, 2, 'COMPLETED', 101, 'HIGH',
                            'rule-v1', 'score-v1', 'feature-v1',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP, 'trace_detection_02'
                        )
                        """, UUID.randomUUID(), transaction.getId()),
                "ck_detection_result_risk_score"
        );
    }

    @Test
    void makesEvidenceImmutableAndRejectsTerminalInsertion() {
        DetectionResult completed = completedResult(
                saveTransaction(UUID.randomUUID())
        );
        DetectionEvidence evidence = evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        completed.getDetectionResultId()
                )
                .get(0);

        assertTriggerViolation(() -> jdbcTemplate.update("""
                UPDATE detection_evidence
                SET display_description = '변경'
                WHERE id = ?
                """, evidence.getId()));
        assertTriggerViolation(() -> jdbcTemplate.update(
                "DELETE FROM detection_evidence WHERE id = ?",
                evidence.getId()
        ));
        assertTriggerViolation(() -> insertRuleEvidence(
                completed.getId(),
                UUID.randomUUID(),
                "RECENT_BENEFICIARY_TRANSFER",
                1
        ));
        assertTriggerViolation(() -> jdbcTemplate.update(
                "DELETE FROM detection_result WHERE id = ?",
                completed.getId()
        ));
    }

    @Test
    void enforcesRuleNonRuleJsonAndOneEvidencePerRule() {
        DetectionResult result = startedResult(saveTransaction(UUID.randomUUID()));
        UUID evidenceId = UUID.randomUUID();
        insertRuleEvidence(
                result.getId(),
                evidenceId,
                "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                0
        );

        assertConstraint(
                () -> insertRuleEvidence(
                        result.getId(),
                        evidenceId,
                        "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
                        1
                ),
                "uq_detection_evidence_business_id"
        );
        assertConstraint(
                () -> insertRuleEvidence(
                        result.getId(),
                        UUID.randomUUID(),
                        "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                        1
                ),
                "uq_detection_evidence_result_rule_code"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        INSERT INTO detection_evidence (
                            evidence_id,
                            detection_result_id,
                            evidence_type,
                            reason_code,
                            display_description,
                            rule_code,
                            rule_version,
                            observation_summary,
                            evidence_occurred_at,
                            sort_order
                        ) VALUES (
                            ?, ?, 'ML', 'ML_SIGNAL', 'ML signal',
                            'RULE_CODE', '1', '{"signal": 1}'::jsonb,
                            CURRENT_TIMESTAMP, 2
                        )
                        """, UUID.randomUUID(), result.getId()),
                "ck_detection_evidence_rule_fields"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        INSERT INTO detection_evidence (
                            evidence_id,
                            detection_result_id,
                            evidence_type,
                            reason_code,
                            display_description,
                            observation_summary,
                            evidence_occurred_at,
                            sort_order
                        ) VALUES (
                            ?, ?, 'ML', 'ML_SIGNAL', 'ML signal',
                            '{}'::jsonb, CURRENT_TIMESTAMP, 3
                        )
                        """, UUID.randomUUID(), result.getId()),
                "ck_detection_evidence_observation_summary"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        INSERT INTO detection_evidence (
                            evidence_id,
                            detection_result_id,
                            evidence_type,
                            reason_code,
                            display_description,
                            observation_summary,
                            evidence_occurred_at,
                            sort_order
                        ) VALUES (
                            ?, ?, 'ML', 'ML_SIGNAL', 'ML signal',
                            '{"signal": 1}'::jsonb,
                            CURRENT_TIMESTAMP, 4
                        )
                        """,
                        UUID.nameUUIDFromBytes(new byte[]{2}),
                        result.getId()
                ),
                "ck_detection_evidence_uuid_v4"
        );
    }

    @Test
    void enforcesAdoptionOwnershipCompletionRiskAndResponseMapping() {
        FinancialTransaction first = saveTransaction(UUID.randomUUID());
        FinancialTransaction second = saveTransaction(UUID.randomUUID());
        DetectionResult completed = completedResult(first);
        DetectionResult pending = createPending(first, "trace_detection_pending");

        jdbcTemplate.update("""
                UPDATE financial_transaction
                SET adopted_detection_result_id = ?,
                    risk_level = 'HIGH'
                WHERE id = ?
                """, completed.getId(), first.getId());
        assertConstraint(
                () -> jdbcTemplate.update("""
                        UPDATE financial_transaction
                        SET adopted_detection_result_id = ?,
                            risk_level = 'HIGH'
                        WHERE id = ?
                        """, completed.getId(), second.getId()),
                "fk_financial_transaction_adopted_detection_result"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        UPDATE financial_transaction
                        SET adopted_detection_result_id = ?,
                            risk_level = 'LOW'
                        WHERE id = ?
                        """, completed.getId(), first.getId()),
                "fk_financial_transaction_adopted_detection_result"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        UPDATE financial_transaction
                        SET adopted_detection_result_id = ?,
                            risk_level = 'LOW'
                        WHERE id = ?
                        """, pending.getId(), first.getId()),
                "ck_financial_transaction_adopted_result_completed"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        UPDATE financial_transaction
                        SET adopted_detection_result_id = NULL,
                            risk_level = 'HIGH'
                        WHERE id = ?
                        """, first.getId()),
                "ck_financial_transaction_adopted_risk"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        UPDATE financial_transaction
                        SET risk_response_outcome = 'APPROVED'
                        WHERE id = ?
                        """, first.getId()),
                "ck_financial_transaction_risk_response_mapping"
        );
        jdbcTemplate.update("""
                UPDATE financial_transaction
                SET risk_response_outcome = 'ADDITIONAL_AUTH_REQUIRED',
                    processing_status = 'FAILED'
                WHERE id = ?
                """, first.getId());

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT processing_status, risk_level, risk_response_outcome
                FROM financial_transaction
                WHERE id = ?
                """, first.getId());
        assertThat(stored).containsEntry("processing_status", "FAILED")
                .containsEntry("risk_level", "HIGH")
                .containsEntry(
                        "risk_response_outcome",
                        "ADDITIONAL_AUTH_REQUIRED"
                );
    }

    @Test
    void acceptsEachApprovedRiskResponseMapping() {
        Map<RiskLevel, String> mappings = Map.of(
                RiskLevel.LOW,
                "APPROVED",
                RiskLevel.MEDIUM,
                "APPROVED_WITH_MONITORING",
                RiskLevel.HIGH,
                "ADDITIONAL_AUTH_REQUIRED",
                RiskLevel.CRITICAL,
                "HELD"
        );

        mappings.forEach((riskLevel, outcome) -> {
            FinancialTransaction transaction =
                    saveTransaction(UUID.randomUUID());
            DetectionResult completed = completedResult(
                    transaction,
                    riskLevel,
                    50
            );

            int updated = jdbcTemplate.update("""
                    UPDATE financial_transaction
                    SET adopted_detection_result_id = ?,
                        risk_level = ?,
                        risk_response_outcome = ?
                    WHERE id = ?
                    """,
                    completed.getId(),
                    riskLevel.name(),
                    outcome,
                    transaction.getId()
            );

            assertThat(updated).isEqualTo(1);
        });
    }

    @Test
    void allocatesMonotonicVersionsUnderConcurrentRequests() throws Exception {
        FinancialTransaction transaction = saveTransaction(UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(
                    () -> createVersionConcurrently(
                            transaction.getTransactionId(),
                            "trace_concurrent_01",
                            ready,
                            start
                    )
            );
            Future<Integer> second = executor.submit(
                    () -> createVersionConcurrently(
                            transaction.getTransactionId(),
                            "trace_concurrent_02",
                            ready,
                            start
                    )
            );

            ready.await();
            start.countDown();

            assertThat(Set.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(1, 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedResultConsumesVersionAndRepositoryOrdersVersionsLazily() {
        FinancialTransaction transaction = saveTransaction(UUID.randomUUID());
        DetectionResult first = createPending(
                transaction,
                "trace_detection_failed"
        );
        persistenceService.fail(
                first.getDetectionResultId(),
                "DEPENDENCY_TIMEOUT",
                Instant.now().plusSeconds(1)
        );
        DetectionResult second = createPending(
                transaction,
                "trace_detection_second"
        );
        entityManager.clear();

        List<DetectionResult> results = resultRepository
                .findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
                        transaction.getTransactionId()
                );
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory()
                        .getPersistenceUnitUtil();

        assertThat(results).extracting(
                DetectionResult::getDetectionResultVersion
        ).containsExactly(2, 1);
        assertThat(second.getDetectionResultVersion()).isEqualTo(2);
        assertThat(persistenceUnitUtil.isLoaded(
                results.get(0),
                "financialTransaction"
        )).isFalse();
    }

    @Test
    void evidenceRepositoryOrdersBySortAndKeepsParentLazy() {
        DetectionResult result = startedResult(
                saveTransaction(UUID.randomUUID())
        );
        persistenceService.complete(
                result.getDetectionResultId(),
                20,
                RiskLevel.MEDIUM,
                Instant.now().plusSeconds(1),
                List.of(
                        beneficiaryDraft(
                                result.getEvaluationCutoffAt(),
                                1
                        ),
                        ruleDraft(result.getEvaluationCutoffAt(), 0)
                )
        );
        entityManager.clear();

        List<DetectionEvidence> evidence = evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        result.getDetectionResultId()
                );
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory()
                        .getPersistenceUnitUtil();

        assertThat(evidence).extracting(DetectionEvidence::getSortOrder)
                .containsExactly(0, 1);
        assertThat(persistenceUnitUtil.isLoaded(
                evidence.get(0),
                "detectionResult"
        )).isFalse();
        assertThat(persistenceUnitUtil.isLoaded(
                evidence.get(0),
                "ruleVersionRef"
        )).isFalse();
    }

    @Test
    void rollsBackEvidenceWhenCompletionCannotBecomeTerminal() {
        DetectionResult result = startedResult(
                saveTransaction(UUID.randomUUID())
        );

        assertThatThrownBy(() -> persistenceService.complete(
                result.getDetectionResultId(),
                101,
                RiskLevel.HIGH,
                Instant.now().plusSeconds(1),
                List.of(ruleDraft(result.getEvaluationCutoffAt(), 0))
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        result.getDetectionResultId()
                )).isEmpty();
        DetectionResult stored = resultRepository.findByDetectionResultId(
                result.getDetectionResultId()
        ).orElseThrow();
        assertThat(stored.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.IN_PROGRESS);
    }

    @Test
    void persistsValidR001ThroughR004EvidenceAtStorageBoundary() {
        DetectionResult result = startedResult(
                saveTransaction(UUID.randomUUID())
        );
        Instant cutoff = result.getEvaluationCutoffAt();

        persistenceService.complete(
                result.getDetectionResultId(),
                75,
                RiskLevel.HIGH,
                Instant.now().plusSeconds(1),
                List.of(
                        ruleDraft(cutoff, 0),
                        deviceDraft(cutoff, 1),
                        securityDraft(cutoff, 2),
                        beneficiaryDraft(cutoff, 3)
                )
        );
        entityManager.clear();

        List<DetectionEvidence> storedEvidence = evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        result.getDetectionResultId()
                );
        DetectionResult storedResult = resultRepository
                .findByDetectionResultId(result.getDetectionResultId())
                .orElseThrow();

        assertThat(storedEvidence)
                .extracting(DetectionEvidence::getRuleCode)
                .containsExactly(
                        RuleEvidenceObservationSummary
                                .TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        RuleEvidenceObservationSummary
                                .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                        RuleEvidenceObservationSummary
                                .RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                        RuleEvidenceObservationSummary
                                .RECENT_BENEFICIARY_TRANSFER
                );
        assertThat(storedResult.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.COMPLETED);
    }

    @Test
    void rejectsInvalidEvidenceWithoutPartialDatabaseState() {
        DetectionResult result = startedResult(
                saveTransaction(UUID.randomUUID())
        );
        Instant cutoff = result.getEvaluationCutoffAt();
        RuleEvidenceDraft validDraft = ruleDraft(cutoff, 0);
        RuleEvidenceDraft validBeneficiary = beneficiaryDraft(cutoff, 1);
        RuleEvidenceDraft invalidBeneficiary = new RuleEvidenceDraft(
                validBeneficiary.ruleVersionId(),
                validBeneficiary.displayDescription(),
                validBeneficiary.observationSummary(),
                validBeneficiary.evidenceOccurredAt().plusSeconds(1),
                validBeneficiary.sortOrder()
        );

        assertThatThrownBy(() -> persistenceService.complete(
                result.getDetectionResultId(),
                25,
                RiskLevel.MEDIUM,
                Instant.now().plusSeconds(1),
                List.of(validDraft, invalidBeneficiary)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidenceOccurredAt")
                .hasMessageContaining(
                        RuleEvidenceObservationSummary
                                .RECENT_BENEFICIARY_TRANSFER
                );

        entityManager.clear();
        assertThat(evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        result.getDetectionResultId()
                )).isEmpty();
        DetectionResult stored = resultRepository.findByDetectionResultId(
                result.getDetectionResultId()
        ).orElseThrow();
        assertThat(stored.getAnalysisStatus())
                .isEqualTo(DetectionAnalysisStatus.IN_PROGRESS);
        assertThat(stored.getRiskScore()).isNull();
        assertThat(stored.getRiskLevel()).isNull();
        assertThat(stored.getAnalysisCompletedAt()).isNull();
    }

    private int createVersionConcurrently(
            UUID transactionId,
            String traceId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        return persistenceService.createPending(
                transactionId,
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                Instant.now(),
                traceId
        ).getDetectionResultVersion();
    }

    private DetectionResult completedResult(
            FinancialTransaction transaction
    ) {
        return completedResult(transaction, RiskLevel.HIGH, 55);
    }

    private DetectionResult completedResult(
            FinancialTransaction transaction,
            RiskLevel riskLevel,
            int riskScore
    ) {
        DetectionResult result = startedResult(transaction);
        persistenceService.complete(
                result.getDetectionResultId(),
                riskScore,
                riskLevel,
                Instant.now().plusSeconds(1),
                List.of(ruleDraft(result.getEvaluationCutoffAt(), 0))
        );
        return resultRepository.findByDetectionResultId(
                result.getDetectionResultId()
        ).orElseThrow();
    }

    private DetectionResult startedResult(
            FinancialTransaction transaction
    ) {
        DetectionResult result = createPending(
                transaction,
                "trace_detection_started"
        );
        persistenceService.start(
                result.getDetectionResultId(),
                Instant.now()
        );
        return resultRepository.findByDetectionResultId(
                result.getDetectionResultId()
        ).orElseThrow();
    }

    private DetectionResult createPending(
            FinancialTransaction transaction,
            String traceId
    ) {
        return persistenceService.createPending(
                transaction.getTransactionId(),
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                transaction.getOccurredAt(),
                traceId
        );
    }

    private RuleEvidenceDraft beneficiaryDraft(
            Instant cutoff,
            int sortOrder
    ) {
        RuleVersion version = publishSeedRuleVersion(
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER
        );
        Instant beneficiaryRegisteredAt = cutoff.minusSeconds(60);
        return new RuleEvidenceDraft(
                version.getRuleVersionId(),
                "최근 등록된 수취인 이체입니다.",
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put(
                                "eventId",
                                "11111111-1111-4111-8111-111111111111"
                        )
                        .put(
                                "beneficiaryRegisteredAt",
                                beneficiaryRegisteredAt.toString()
                        )
                        .put("elapsedSeconds", 60)
                        .put("windowSeconds", 86400),
                beneficiaryRegisteredAt,
                sortOrder
        );
    }

    private RuleEvidenceDraft deviceDraft(Instant cutoff, int sortOrder) {
        RuleVersion version = publishSeedRuleVersion(
                RuleEvidenceObservationSummary
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
        );
        Instant deviceRegisteredAt = cutoff.minusSeconds(60);
        return new RuleEvidenceDraft(
                version.getRuleVersionId(),
                "최근 등록된 기기의 고액 이체입니다.",
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000")
                        .put(
                                "eventId",
                                "11111111-1111-4111-8111-111111111111"
                        )
                        .put(
                                "deviceRegisteredAt",
                                deviceRegisteredAt.toString()
                        )
                        .put("elapsedSeconds", 60)
                        .put("windowSeconds", 86400),
                deviceRegisteredAt,
                sortOrder
        );
    }

    private RuleEvidenceDraft securityDraft(Instant cutoff, int sortOrder) {
        RuleVersion version = publishSeedRuleVersion(
                RuleEvidenceObservationSummary
                        .RECENT_SECURITY_CHANGE_HIGH_AMOUNT
        );
        Instant passwordChangedAt = cutoff.minusSeconds(120);
        Instant transferLimitChangedAt = cutoff.minusSeconds(60);
        return new RuleEvidenceDraft(
                version.getRuleVersionId(),
                "최근 보안정보 변경 후 고액 이체입니다.",
                objectMapper.createObjectNode()
                        .put("observedAmount", "10000000")
                        .put("amountThreshold", "10000000")
                        .put(
                                "passwordChangedEventId",
                                "11111111-1111-4111-8111-111111111111"
                        )
                        .put(
                                "passwordChangedAt",
                                passwordChangedAt.toString()
                        )
                        .put(
                                "transferLimitChangedEventId",
                                "22222222-2222-4222-8222-222222222222"
                        )
                        .put(
                                "transferLimitChangedAt",
                                transferLimitChangedAt.toString()
                        )
                        .put("elapsedSeconds", 60)
                        .put("windowSeconds", 86400),
                transferLimitChangedAt,
                sortOrder
        );
    }

    private RuleEvidenceDraft ruleDraft(Instant cutoff, int sortOrder) {
        RuleVersion version = publishSeedRuleVersion(
                RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT
        );
        return new RuleEvidenceDraft(
                version.getRuleVersionId(),
                "KRW 이체 금액이 기준 이상입니다.",
                amountSummary(),
                cutoff,
                sortOrder
        );
    }

    private RuleVersion publishSeedRuleVersion(String ruleCode) {
        RuleVersion version = ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(ruleCode, 1)
                .orElseThrow();
        if (version.getStatus() == RuleVersionStatus.PUBLISHED) {
            return version;
        }
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
        return ruleVersionRepository.findByRuleVersionId(
                version.getRuleVersionId()
        ).orElseThrow();
    }

    private ObjectNode amountSummary() {
        return objectMapper.createObjectNode()
                .put("observedAmount", "10000000")
                .put("amountThreshold", "10000000");
    }

    private FinancialTransaction saveTransaction(UUID transactionId) {
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                Instant.now()
                        .minus(1, ChronoUnit.MINUTES)
                        .truncatedTo(ChronoUnit.MICROS),
                "cust_ref_detection_integration",
                "acct_ref_detection_sender",
                "acct_ref_detection_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_detection"
        ));
    }

    private long insertPendingResult(
            long transactionPk,
            UUID resultId,
            int version
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO detection_result (
                    detection_result_id,
                    financial_transaction_id,
                    detection_result_version,
                    analysis_status,
                    rule_set_version,
                    scoring_policy_version,
                    feature_version,
                    evaluation_cutoff_at,
                    analysis_trace_id
                ) VALUES (
                    ?, ?, ?, 'PENDING',
                    'rule-v1', 'score-v1', 'feature-v1',
                    CURRENT_TIMESTAMP, 'trace_detection_01'
                )
                RETURNING id
                """, Long.class, resultId, transactionPk, version);
    }

    private void insertRuleEvidence(
            long resultPk,
            UUID evidenceId,
            String ruleCode,
            int sortOrder
    ) {
        jdbcTemplate.update("""
                INSERT INTO detection_evidence (
                    evidence_id,
                    detection_result_id,
                    evidence_type,
                    reason_code,
                    display_description,
                    score_contribution,
                    rule_code,
                    rule_version,
                    observation_summary,
                    evidence_occurred_at,
                    sort_order
                ) VALUES (
                    ?, ?, 'RULE', ?, 'Rule evidence', 15, ?, '1',
                    '{"observedAmount": "10000000",
                      "amountThreshold": "10000000"}'::jsonb,
                    CURRENT_TIMESTAMP, ?
                )
                """, evidenceId, resultPk, ruleCode, ruleCode, sortOrder);
    }

    private Set<String> columns(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                """, String.class, tableName));
    }

    private Set<String> constraints(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT constraint_record.conname
                FROM pg_constraint constraint_record
                JOIN pg_class table_record
                  ON table_record.oid = constraint_record.conrelid
                WHERE table_record.relname = ?
                """, String.class, tableName));
    }

    private Set<String> indexes(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = ?
                """, String.class, tableName));
    }

    private Set<String> triggers() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT trigger_name
                FROM information_schema.triggers
                WHERE trigger_schema = 'public'
                  AND event_object_table IN (
                    'detection_result',
                    'detection_evidence',
                    'financial_transaction'
                  )
                """, String.class));
    }

    private void assertConstraint(Runnable operation, String constraint) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    SQLException sqlException = findSqlException(exception);
                    assertThatObject(sqlException).isNotNull();
                    assertThat(sqlException.getMessage()).contains(constraint);
                });
    }

    private void assertTriggerViolation(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    SQLException sqlException = findSqlException(exception);
                    assertThatObject(sqlException).isNotNull();
                    assertThat(sqlException.getSQLState()).isEqualTo("55000");
                });
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }
}
