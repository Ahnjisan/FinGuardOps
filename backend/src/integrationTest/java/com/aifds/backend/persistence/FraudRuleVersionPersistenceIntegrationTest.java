package com.aifds.backend.persistence;

import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RuleEvidenceObservationSummary;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.service.DetectionResultPersistenceService;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.FraudRuleLifecycleStatus;
import com.aifds.backend.rule.entity.RuleConditionDefinition;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.exception.RuleVersionPeriodOverlapException;
import com.aifds.backend.rule.repository.FraudRuleRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudRuleVersionPersistenceIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final Instant FIRST_START =
            Instant.parse("2026-08-01T00:00:00Z");
    private static final String AMOUNT_RULE =
            RuleEvidenceObservationSummary.TRANSFER_ABSOLUTE_HIGH_AMOUNT;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private FraudRuleRepository fraudRuleRepository;

    @Autowired
    private RuleVersionRepository ruleVersionRepository;

    @Autowired
    private RuleVersionLifecycleService lifecycleService;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private DetectionResultPersistenceService detectionPersistenceService;

    @Autowired
    private DetectionEvidenceRepository evidenceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void migrationCreatesExtensionTablesConstraintsIndexesAndTriggers() {
        assertThat(flyway.info().applied()).hasSize(5);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_extension
                WHERE extname = 'btree_gist'
                """,
                Integer.class
        )).isEqualTo(1);
        assertThat(columns("fraud_rule")).containsExactlyInAnyOrder(
                "id",
                "fraud_rule_id",
                "rule_code",
                "name",
                "description",
                "lifecycle_status",
                "concurrency_version",
                "created_at",
                "updated_at"
        );
        assertThat(columns("rule_version")).containsExactlyInAnyOrder(
                "id",
                "rule_version_id",
                "fraud_rule_id",
                "version_number",
                "status",
                "reason_code",
                "weight",
                "condition_definition",
                "effective_from",
                "effective_to",
                "created_at",
                "published_at",
                "concurrency_version"
        );
        assertThat(constraints("fraud_rule")).containsExactlyInAnyOrder(
                "pk_fraud_rule",
                "uq_fraud_rule_business_id",
                "uq_fraud_rule_rule_code",
                "ck_fraud_rule_uuid_v4",
                "ck_fraud_rule_rule_code",
                "ck_fraud_rule_name",
                "ck_fraud_rule_description",
                "ck_fraud_rule_lifecycle_status",
                "ck_fraud_rule_concurrency_version",
                "ck_fraud_rule_timestamps"
        );
        assertThat(constraints("rule_version")).containsExactlyInAnyOrder(
                "pk_rule_version",
                "uq_rule_version_business_id",
                "uq_rule_version_rule_number",
                "fk_rule_version_fraud_rule",
                "ck_rule_version_uuid_v4",
                "ck_rule_version_number",
                "ck_rule_version_status",
                "ck_rule_version_reason_code",
                "ck_rule_version_weight",
                "ck_rule_version_condition_definition",
                "ck_rule_version_effective_period",
                "ck_rule_version_status_fields",
                "ck_rule_version_concurrency_version",
                "ex_rule_version_published_effective_period"
        );
        assertThat(indexes("rule_version")).contains(
                "ix_rule_version_rule_status_effective",
                "ex_rule_version_published_effective_period"
        );
        assertThat(triggers("fraud_rule")).containsExactly(
                "tg_fraud_rule_history_guard"
        );
        assertThat(triggers("rule_version")).containsExactly(
                "tg_rule_version_history_guard"
        );
    }

    @Test
    void seedsFourActiveRulesAndFourDraftExperimentalVersions() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT
                    rule.fraud_rule_id::text AS fraud_rule_id,
                    rule.rule_code,
                    rule.lifecycle_status,
                    version.rule_version_id::text AS rule_version_id,
                    version.version_number,
                    version.status,
                    version.reason_code,
                    version.weight,
                    version.condition_definition,
                    version.effective_from,
                    version.effective_to,
                    version.published_at
                FROM fraud_rule rule
                JOIN rule_version version
                    ON version.fraud_rule_id = rule.id
                ORDER BY rule.rule_code
                """);

        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("lifecycle_status")).isEqualTo("ACTIVE");
            assertThat(row.get("version_number")).isEqualTo(1);
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("reason_code"))
                    .isEqualTo(row.get("rule_code"));
            assertThat(row.get("effective_from")).isNull();
            assertThat(row.get("effective_to")).isNull();
            assertThat(row.get("published_at")).isNull();
            assertCanonicalUuidV4((String) row.get("fraud_rule_id"));
            assertCanonicalUuidV4((String) row.get("rule_version_id"));
        });
        assertThat(rows).extracting(row -> row.get("weight"))
                .containsExactlyInAnyOrder(15, 20, 40, 10);

        rows.forEach(row -> {
            try {
                assertThat(RuleConditionDefinition.from(
                        (String) row.get("rule_code"),
                        objectMapper.readTree(
                                row.get("condition_definition").toString()
                        )
                ).toJson()).isNotNull();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    void enforcesUuidCodeVersionWeightJsonStatusAndPeriodConstraints() {
        FraudRule rule = seedRule(AMOUNT_RULE);

        assertConstraint(
                () -> insertDraftVersion(
                        rule.getId(),
                        UUID.nameUUIDFromBytes(new byte[]{1}),
                        2,
                        AMOUNT_RULE,
                        15,
                        amountCondition().toString()
                ),
                "ck_rule_version_uuid_v4"
        );
        assertConstraint(
                () -> insertDraftVersion(
                        rule.getId(),
                        UUID.randomUUID(),
                        1,
                        AMOUNT_RULE,
                        15,
                        amountCondition().toString()
                ),
                "uq_rule_version_rule_number"
        );
        assertConstraint(
                () -> insertDraftVersion(
                        rule.getId(),
                        UUID.randomUUID(),
                        2,
                        "lower_case",
                        15,
                        amountCondition().toString()
                ),
                "ck_rule_version_reason_code"
        );
        assertConstraint(
                () -> insertDraftVersion(
                        rule.getId(),
                        UUID.randomUUID(),
                        2,
                        AMOUNT_RULE,
                        0,
                        amountCondition().toString()
                ),
                "ck_rule_version_weight"
        );
        assertConstraint(
                () -> insertDraftVersion(
                        rule.getId(),
                        UUID.randomUUID(),
                        2,
                        AMOUNT_RULE,
                        15,
                        "{}"
                ),
                "ck_rule_version_condition_definition"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        INSERT INTO rule_version (
                            rule_version_id,
                            fraud_rule_id,
                            version_number,
                            status,
                            reason_code,
                            weight,
                            condition_definition,
                            effective_from,
                            effective_to
                        ) VALUES (
                            ?, ?, 2, 'DRAFT', ?, 15, ?::jsonb,
                            ?::timestamptz, ?::timestamptz
                        )
                        """,
                        UUID.randomUUID(),
                        rule.getId(),
                        AMOUNT_RULE,
                        amountCondition().toString(),
                        FIRST_START.toString(),
                        FIRST_START.toString()
                ),
                "ck_rule_version_effective_period"
        );
        assertConstraint(
                () -> jdbcTemplate.update("""
                        INSERT INTO rule_version (
                            rule_version_id,
                            fraud_rule_id,
                            version_number,
                            status,
                            reason_code,
                            weight,
                            condition_definition,
                            effective_from
                        ) VALUES (
                            ?, ?, 2, 'PUBLISHED', ?, 15, ?::jsonb,
                            ?::timestamptz
                        )
                        """,
                        UUID.randomUUID(),
                        rule.getId(),
                        AMOUNT_RULE,
                        amountCondition().toString(),
                        FIRST_START.toString()
                ),
                "ck_rule_version_status_fields"
        );
    }

    @Test
    void enforcesLifecycleTransitionsPublishedImmutabilityAndDeletion() {
        FraudRule rule = seedRule(AMOUNT_RULE);
        RuleVersion version = prepareSeedDraft(
                AMOUNT_RULE,
                FIRST_START,
                null
        );
        lifecycleService.publish(
                version.getRuleVersionId(),
                FIRST_START.minusSeconds(60)
        );

        jdbcTemplate.update("""
                UPDATE fraud_rule
                SET lifecycle_status = 'RETIRED',
                    concurrency_version = concurrency_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, rule.getId());
        assertTriggerViolation(() -> jdbcTemplate.update("""
                UPDATE fraud_rule
                SET lifecycle_status = 'ACTIVE',
                    concurrency_version = concurrency_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, rule.getId()));
        assertTriggerViolation(() -> jdbcTemplate.update("""
                UPDATE rule_version
                SET weight = 20,
                    concurrency_version = concurrency_version + 1
                WHERE id = ?
                """, version.getId()));
        assertTriggerViolation(() -> jdbcTemplate.update(
                "DELETE FROM rule_version WHERE id = ?",
                version.getId()
        ));

        jdbcTemplate.update("""
                INSERT INTO fraud_rule (
                    fraud_rule_id,
                    rule_code,
                    name,
                    description,
                    lifecycle_status
                ) VALUES (?, 'DELETE_GUARD_TEST', '삭제 테스트', '삭제 방지', 'ACTIVE')
                """, UUID.randomUUID());
        Long unreferencedRuleId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM fraud_rule
                WHERE rule_code = 'DELETE_GUARD_TEST'
                """,
                Long.class
        );
        assertTriggerViolation(() -> jdbcTemplate.update(
                "DELETE FROM fraud_rule WHERE id = ?",
                unreferencedRuleId
        ));
    }

    @Test
    void permitsDraftChangesAndPublishedEffectiveToOnlyOnce() {
        RuleVersion draft = seedVersion(AMOUNT_RULE);
        lifecycleService.updateDraft(
                draft.getRuleVersionId(),
                draft.getReasonCode(),
                20,
                amountCondition().put("amountThreshold", "20000000"),
                FIRST_START,
                null
        );
        lifecycleService.publish(
                draft.getRuleVersionId(),
                FIRST_START.minusSeconds(60)
        );
        lifecycleService.closeEffectivePeriod(
                draft.getRuleVersionId(),
                FIRST_START.plusSeconds(3600)
        );

        assertThat(ruleVersionRepository.findByRuleVersionId(
                draft.getRuleVersionId()
        ).orElseThrow().getEffectiveTo())
                .isEqualTo(FIRST_START.plusSeconds(3600));
        assertTriggerViolation(() -> jdbcTemplate.update("""
                UPDATE rule_version
                SET effective_to = ?::timestamptz,
                    concurrency_version = concurrency_version + 1
                WHERE id = ?
                """,
                FIRST_START.plusSeconds(7200).toString(),
                draft.getId()
        ));
    }

    @Test
    void usesHalfOpenPeriodsAllowsAdjacencyAndReturnsOneExecutableVersion() {
        RuleVersion first = prepareSeedDraft(
                AMOUNT_RULE,
                FIRST_START,
                FIRST_START.plusSeconds(3600)
        );
        lifecycleService.publish(
                first.getRuleVersionId(),
                FIRST_START.minusSeconds(120)
        );
        RuleVersion second = newDraft(
                seedRule(AMOUNT_RULE),
                2,
                FIRST_START.plusSeconds(3600),
                null
        );
        second = ruleVersionRepository.saveAndFlush(second);
        lifecycleService.publish(
                second.getRuleVersionId(),
                FIRST_START.minusSeconds(60)
        );

        assertThat(ruleVersionRepository.findExecutableVersion(
                AMOUNT_RULE,
                FIRST_START.minus(1, ChronoUnit.MICROS)
        )).isEmpty();
        assertThat(ruleVersionRepository.findExecutableVersion(
                AMOUNT_RULE,
                FIRST_START
        )).get().extracting(RuleVersion::getVersionNumber).isEqualTo(1);
        assertThat(ruleVersionRepository.findExecutableVersion(
                AMOUNT_RULE,
                FIRST_START.plusSeconds(3600)
                        .minus(1, ChronoUnit.MICROS)
        )).get().extracting(RuleVersion::getVersionNumber).isEqualTo(1);
        assertThat(ruleVersionRepository.findExecutableVersion(
                AMOUNT_RULE,
                FIRST_START.plusSeconds(3600)
        )).get().extracting(RuleVersion::getVersionNumber).isEqualTo(2);
        assertThat(ruleVersionRepository
                .findAllByFraudRule_RuleCodeOrderByVersionNumberDesc(
                        AMOUNT_RULE
                )).extracting(RuleVersion::getVersionNumber)
                .containsExactly(2, 1);
    }

    @Test
    void rejectsOverlappingPublishedPeriodsWithExclusionConstraint() {
        RuleVersion first = prepareSeedDraft(
                AMOUNT_RULE,
                FIRST_START,
                FIRST_START.plusSeconds(3600)
        );
        lifecycleService.publish(
                first.getRuleVersionId(),
                FIRST_START.minusSeconds(120)
        );
        RuleVersion overlapping = newDraft(
                seedRule(AMOUNT_RULE),
                2,
                FIRST_START.plusSeconds(1800),
                null
        );
        overlapping = ruleVersionRepository.saveAndFlush(overlapping);
        Long overlappingId = overlapping.getId();

        assertConstraint(
                () -> jdbcTemplate.update("""
                        UPDATE rule_version
                        SET status = 'PUBLISHED',
                            published_at = ?::timestamptz,
                            concurrency_version = concurrency_version + 1
                        WHERE id = ?
                        """,
                        FIRST_START.minusSeconds(60).toString(),
                        overlappingId
                ),
                "ex_rule_version_published_effective_period"
        );
    }

    @Test
    void serializesConcurrentPublishingAndReturnsBusinessOverlapError()
            throws Exception {
        RuleVersion first = prepareSeedDraft(
                AMOUNT_RULE,
                FIRST_START,
                null
        );
        RuleVersion second = newDraft(
                seedRule(AMOUNT_RULE),
                2,
                FIRST_START,
                null
        );
        second = ruleVersionRepository.saveAndFlush(second);
        UUID firstId = first.getRuleVersionId();
        UUID secondId = second.getRuleVersionId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = executor.submit(
                    () -> publishConcurrently(firstId, ready, start)
            );
            Future<String> secondResult = executor.submit(
                    () -> publishConcurrently(secondId, ready, start)
            );
            ready.await();
            start.countDown();

            assertThat(Set.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder("PUBLISHED", "OVERLAP");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM rule_version
                    WHERE fraud_rule_id = ?
                      AND status = 'PUBLISHED'
                    """, Integer.class, seedRule(AMOUNT_RULE).getId()))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repositoriesUseBusinessIdsAndKeepRelationshipsLazy() {
        FraudRule rule = seedRule(AMOUNT_RULE);
        RuleVersion version = seedVersion(AMOUNT_RULE);
        UUID ruleId = rule.getFraudRuleId();
        UUID versionId = version.getRuleVersionId();
        entityManager.clear();

        FraudRule loadedRule = fraudRuleRepository
                .findByFraudRuleId(ruleId)
                .orElseThrow();
        RuleVersion loadedVersion = ruleVersionRepository
                .findByRuleVersionId(versionId)
                .orElseThrow();
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory()
                        .getPersistenceUnitUtil();

        assertThat(fraudRuleRepository.findByRuleCode(AMOUNT_RULE))
                .get().extracting(FraudRule::getFraudRuleId)
                .isEqualTo(loadedRule.getFraudRuleId());
        assertThat(ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(AMOUNT_RULE, 1))
                .get().extracting(RuleVersion::getRuleVersionId)
                .isEqualTo(loadedVersion.getRuleVersionId());
        assertThat(persistenceUnitUtil.isLoaded(
                loadedVersion,
                "fraudRule"
        )).isFalse();
    }

    @Test
    void detectsOptimisticLockConflicts() {
        FraudRule first = fraudRuleRepository.findByRuleCode(AMOUNT_RULE)
                .orElseThrow();
        FraudRule stale = fraudRuleRepository.findByRuleCode(AMOUNT_RULE)
                .orElseThrow();

        first.updateDetails("첫 번째 수정", "첫 번째 설명");
        fraudRuleRepository.saveAndFlush(first);
        stale.updateDetails("늦은 수정", "늦은 설명");

        assertThatThrownBy(() -> fraudRuleRepository.saveAndFlush(stale))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void detectsRuleVersionOptimisticLockConflicts() {
        RuleVersion first = ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(AMOUNT_RULE, 1)
                .orElseThrow();
        RuleVersion stale = ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(AMOUNT_RULE, 1)
                .orElseThrow();

        first.withdraw();
        ruleVersionRepository.saveAndFlush(first);
        stale.withdraw();

        assertThatThrownBy(() -> ruleVersionRepository.saveAndFlush(stale))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void supportsLegacyNullFkAndEnforcesReferencedEvidenceSnapshot() {
        DetectionResult result = startedDetectionResult();
        RuleVersion published = prepareSeedDraft(
                AMOUNT_RULE,
                FIRST_START,
                null
        );
        lifecycleService.publish(
                published.getRuleVersionId(),
                FIRST_START.minusSeconds(60)
        );
        published = ruleVersionRepository.findByRuleVersionId(
                published.getRuleVersionId()
        ).orElseThrow();
        Long publishedPk = published.getId();

        insertEvidence(
                result.getId(),
                null,
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                "1",
                RuleEvidenceObservationSummary.RECENT_BENEFICIARY_TRANSFER,
                10,
                0
        );
        assertConstraint(
                () -> insertEvidence(
                        result.getId(),
                        publishedPk,
                        AMOUNT_RULE,
                        "1",
                        RuleEvidenceObservationSummary
                                .RECENT_BENEFICIARY_TRANSFER,
                        15,
                        1
                ),
                "ck_detection_evidence_rule_version_snapshot"
        );
        insertEvidence(
                result.getId(),
                publishedPk,
                AMOUNT_RULE,
                "1",
                AMOUNT_RULE,
                15,
                1
        );
        entityManager.clear();

        List<DetectionEvidence> evidence = evidenceRepository
                .findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
                        result.getDetectionResultId()
                );
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory()
                        .getPersistenceUnitUtil();

        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).getRuleVersionRef()).isNull();
        assertThat(persistenceUnitUtil.isLoaded(
                evidence.get(1),
                "ruleVersionRef"
        )).isFalse();
    }

    private String publishConcurrently(
            UUID ruleVersionId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        try {
            lifecycleService.publish(ruleVersionId, Instant.now());
            return "PUBLISHED";
        } catch (RuleVersionPeriodOverlapException exception) {
            return "OVERLAP";
        }
    }

    private RuleVersion prepareSeedDraft(
            String ruleCode,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        RuleVersion version = seedVersion(ruleCode);
        lifecycleService.updateDraft(
                version.getRuleVersionId(),
                version.getReasonCode(),
                version.getWeight(),
                version.getConditionDefinition(),
                effectiveFrom,
                effectiveTo
        );
        return ruleVersionRepository.findByRuleVersionId(
                version.getRuleVersionId()
        ).orElseThrow();
    }

    private RuleVersion newDraft(
            FraudRule rule,
            int versionNumber,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        return RuleVersion.draft(
                rule,
                versionNumber,
                AMOUNT_RULE,
                15,
                amountCondition(),
                effectiveFrom,
                effectiveTo
        );
    }

    private FraudRule seedRule(String ruleCode) {
        return fraudRuleRepository.findByRuleCode(ruleCode).orElseThrow();
    }

    private RuleVersion seedVersion(String ruleCode) {
        return ruleVersionRepository
                .findByFraudRule_RuleCodeAndVersionNumber(ruleCode, 1)
                .orElseThrow();
    }

    private DetectionResult startedDetectionResult() {
        FinancialTransaction transaction = transactionRepository.saveAndFlush(
                new FinancialTransaction(
                        UUID.randomUUID(),
                        TransactionType.ACCOUNT_TRANSFER,
                        new BigDecimal("10000000"),
                        "KRW",
                        Instant.now().minusSeconds(60),
                        "cust_ref_rule_version_evidence",
                        "acct_ref_rule_version_sender",
                        "acct_ref_rule_version_recipient",
                        TransactionChannel.MOBILE_BANKING,
                        "device_ref_rule_version"
                )
        );
        DetectionResult result = detectionPersistenceService.createPending(
                transaction.getTransactionId(),
                "rule-v1",
                "score-v1",
                "feature-v1",
                null,
                transaction.getOccurredAt(),
                "trace_rule_version_evidence"
        );
        detectionPersistenceService.start(
                result.getDetectionResultId(),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        return result;
    }

    private void insertEvidence(
            long detectionResultId,
            Long ruleVersionId,
            String ruleCode,
            String ruleVersion,
            String reasonCode,
            int scoreContribution,
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
                    rule_version_id,
                    observation_summary,
                    evidence_occurred_at,
                    sort_order
                ) VALUES (
                    ?, ?, 'RULE', ?, 'Rule evidence',
                    ?, ?, ?, ?, '{"observed": true}'::jsonb,
                    CURRENT_TIMESTAMP, ?
                )
                """,
                UUID.randomUUID(),
                detectionResultId,
                reasonCode,
                scoreContribution,
                ruleCode,
                ruleVersion,
                ruleVersionId,
                sortOrder
        );
    }

    private void insertDraftVersion(
            long fraudRuleId,
            UUID ruleVersionId,
            int versionNumber,
            String reasonCode,
            int weight,
            String conditionDefinition
    ) {
        jdbcTemplate.update("""
                INSERT INTO rule_version (
                    rule_version_id,
                    fraud_rule_id,
                    version_number,
                    status,
                    reason_code,
                    weight,
                    condition_definition
                ) VALUES (?, ?, ?, 'DRAFT', ?, ?, ?::jsonb)
                """,
                ruleVersionId,
                fraudRuleId,
                versionNumber,
                reasonCode,
                weight,
                conditionDefinition
        );
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
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
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = ?::regclass
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

    private List<String> triggers(String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT trigger_name
                FROM information_schema.triggers
                WHERE event_object_schema = 'public'
                  AND event_object_table = ?
                ORDER BY trigger_name
                """, String.class, tableName);
    }

    private void assertCanonicalUuidV4(String value) {
        UUID uuid = UUID.fromString(value);
        assertThat(uuid.toString()).isEqualTo(value);
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    private void assertConstraint(Runnable operation, String constraint) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> assertThat(
                        findSqlException(exception).getMessage()
                ).contains(constraint));
    }

    private void assertTriggerViolation(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> assertThat(
                        findSqlException(exception).getSQLState()
                ).isEqualTo("55000"));
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("SQLException was not found", throwable);
    }
}
