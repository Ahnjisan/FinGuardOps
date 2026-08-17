package com.aifds.backend.persistence;

import com.aifds.backend.rule.contract.CanonicalRuleSetVersionCalculator;
import com.aifds.backend.rule.contract.RuleV1ExecutionPlanRegistry;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationResult;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuleV1DefaultRuleSetPublicationIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final Instant EFFECTIVE_FROM =
            Instant.parse("2999-01-01T00:00:00Z");
    private static final String GOLDEN_RULE_SET_VERSION =
            "31299ea02656c1a5c72f2ead74b5ca468d087b4080249e5915d8882164d8121e";

    @Autowired
    private RuleV1DefaultRuleSetPublicationService publicationService;

    @Autowired
    private RuleVersionRepository ruleVersionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void publishesFreshV5DraftsAsExactlyFourExecutableVersions() {
        RuleV1DefaultRuleSetPublicationResult result =
                publicationService.publish(EFFECTIVE_FROM);

        List<RuleVersion> executable =
                ruleVersionRepository.findAllExecutableVersions(EFFECTIVE_FROM);
        List<RuleV1ExecutionPlanRegistry.RuleVersionIdentity> identities =
                executable.stream().map(this::identity).toList();
        List<RuleV1ExecutionPlanRegistry.CanonicalRule> canonical =
                RuleV1ExecutionPlanRegistry.canonicalize(identities);

        assertThat(result.outcome()).isEqualTo(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .PUBLISHED
        );
        assertThat(executable).hasSize(4).allSatisfy(version -> {
            assertThat(version.getStatus()).isEqualTo(
                    RuleVersionStatus.PUBLISHED
            );
            assertThat(version.getEffectiveFrom()).isEqualTo(EFFECTIVE_FROM);
            assertThat(version.getPublishedAt()).isEqualTo(result.publishedAt());
        });
        assertThat(canonical).extracting(
                item -> item.capability().ruleId()
        ).containsExactly(
                RuleV1ExecutionPlanRegistry.CanonicalRuleId.R001,
                RuleV1ExecutionPlanRegistry.CanonicalRuleId.R002,
                RuleV1ExecutionPlanRegistry.CanonicalRuleId.R003,
                RuleV1ExecutionPlanRegistry.CanonicalRuleId.R004
        );
        assertThat(canonical).extracting(
                RuleV1ExecutionPlanRegistry.CanonicalRule::executionOrder
        ).containsExactly(1, 2, 3, 4);
        assertThat(new CanonicalRuleSetVersionCalculator()
                .calculate(identities)).isEqualTo(GOLDEN_RULE_SET_VERSION);
        assertThat(result.ruleSetVersion()).isEqualTo(GOLDEN_RULE_SET_VERSION);
    }

    @Test
    void hidesAllFourUpdatesUntilCommitThenExposesThemTogether()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            Future<RuleV1DefaultRuleSetPublicationResult> publication =
                    executor.submit(() -> new TransactionTemplate(
                            transactionManager
                    ).execute(status -> {
                        RuleV1DefaultRuleSetPublicationResult result =
                                publicationService.publish(EFFECTIVE_FROM);
                        flushed.countDown();
                        await(allowCommit);
                        return result;
                    }));

            assertThat(flushed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(publishedCount()).isZero();
            allowCommit.countDown();
            assertThat(publication.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThat(publishedCount()).isEqualTo(4);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rollsBackEveryVersionWhenTheLastUpdateFails() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_default_r004_publication()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.rule_version_id =
                            '20000000-0000-4000-8000-000000000004'::uuid
                            AND NEW.status = 'PUBLISHED' THEN
                        RAISE EXCEPTION 'forced R004 publication failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_reject_default_r004_publication
                BEFORE UPDATE ON rule_version
                FOR EACH ROW
                EXECUTE FUNCTION reject_default_r004_publication()
                """);
        try {
            assertThatThrownBy(
                    () -> publicationService.publish(EFFECTIVE_FROM)
            ).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS
                        tg_reject_default_r004_publication ON rule_version
                    """);
            jdbcTemplate.execute(
                    "DROP FUNCTION IF EXISTS reject_default_r004_publication()"
            );
        }

        assertThat(publishedCount()).isZero();
        assertThat(draftCount()).isEqualTo(4);
    }

    @Test
    void concurrentPublicationCompletesWithoutDeadlockAtOneFinalState()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RuleV1DefaultRuleSetPublicationResult> first =
                    executor.submit(() -> publishAfterBarrier(ready, start));
            Future<RuleV1DefaultRuleSetPublicationResult> second =
                    executor.submit(() -> publishAfterBarrier(ready, start));
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RuleV1DefaultRuleSetPublicationResult firstResult =
                    first.get(30, TimeUnit.SECONDS);
            RuleV1DefaultRuleSetPublicationResult secondResult =
                    second.get(30, TimeUnit.SECONDS);

            assertThat(Set.of(firstResult.outcome(), secondResult.outcome()))
                    .containsExactlyInAnyOrder(
                            RuleV1DefaultRuleSetPublicationResult
                                    .PublicationOutcome.PUBLISHED,
                            RuleV1DefaultRuleSetPublicationResult
                                    .PublicationOutcome.ALREADY_PUBLISHED
                    );
            assertThat(firstResult.publishedAt())
                    .isEqualTo(secondResult.publishedAt());
            assertThat(firstResult.ruleSetVersion())
                    .isEqualTo(secondResult.ruleSetVersion())
                    .isEqualTo(GOLDEN_RULE_SET_VERSION);
            assertThat(publishedCount()).isEqualTo(4);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMixedLifecycleStateWithoutChangingTheOtherDrafts() {
        jdbcTemplate.update("""
                UPDATE rule_version
                SET status = 'PUBLISHED',
                    effective_from = ?,
                    published_at = CURRENT_TIMESTAMP
                WHERE rule_version_id =
                    '20000000-0000-4000-8000-000000000001'::uuid
                """, Timestamp.from(EFFECTIVE_FROM));

        assertThatThrownBy(() -> publicationService.publish(EFFECTIVE_FROM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all DRAFT or all PUBLISHED");
        assertThat(publishedCount()).isEqualTo(1);
        assertThat(draftCount()).isEqualTo(3);
    }

    @Test
    void exactRetryIsIdempotentAndReturnsTheOriginalPublicationMetadata() {
        RuleV1DefaultRuleSetPublicationResult first =
                publicationService.publish(EFFECTIVE_FROM);
        RuleV1DefaultRuleSetPublicationResult retry =
                publicationService.publish(EFFECTIVE_FROM);

        assertThat(retry.outcome()).isEqualTo(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .ALREADY_PUBLISHED
        );
        assertThat(retry.effectiveFrom()).isEqualTo(first.effectiveFrom());
        assertThat(retry.publishedAt()).isEqualTo(first.publishedAt());
        assertThat(retry.ruleVersionIds()).isEqualTo(first.ruleVersionIds());
        assertThat(retry.ruleSetVersion()).isEqualTo(first.ruleSetVersion());
        assertThat(publishedCount()).isEqualTo(4);
    }

    private RuleV1DefaultRuleSetPublicationResult publishAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return publicationService.publish(EFFECTIVE_FROM);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", exception);
        }
    }

    private int publishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule_version WHERE status = 'PUBLISHED'",
                Integer.class
        );
    }

    private int draftCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule_version WHERE status = 'DRAFT'",
                Integer.class
        );
    }

    private RuleV1ExecutionPlanRegistry.RuleVersionIdentity identity(
            RuleVersion version
    ) {
        return new RuleV1ExecutionPlanRegistry.RuleVersionIdentity(
                version.getFraudRule().getFraudRuleId(),
                version.getRuleVersionId(),
                version.getFraudRule().getRuleCode(),
                version.getVersionNumber()
        );
    }
}
