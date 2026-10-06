package com.aifds.backend.persistence;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiReportOutboxIntegrationTest {
    @Test
    void v18KeepsOutboxAtomicAndClaimsSingleExecutionAcrossWorkers() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            var executions = new AiReportExecutionRepository(jdbc);
            var outbox = new OutboxRepository(jdbc,
                    new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules()));
            UUID caseId = UUID.randomUUID();
            long[] references = fixture(jdbc, caseId);
            UUID[] rolledBackExecution = new UUID[1];
            transactions.executeWithoutResult(status -> {
                var row = executions.insert(references[0], references[1], 1,
                        "rollback-prompt", "model-1");
                rolledBackExecution[0] = row.executionId();
                UUID rolledBackRequest = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,
                        idempotency_key,fingerprint,requested_by,detection_result_version,
                        prompt_version,model_version,status,trace_id)
                        VALUES (?,?,?,'rollback-key',?,'synthetic-user',1,
                        'rollback-prompt','model-1','PENDING','rollback-trace')
                        """, rolledBackRequest, references[0], row.id(), "c".repeat(64));
                outbox.insert(AiReportExecutionCreated.newExecution(row.executionId(),
                        rolledBackRequest, caseId, 1, "rollback-prompt", "model-1",
                        "rollback-trace"));
                status.setRollbackOnly();
            });
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_execution WHERE execution_id=?",
                    Integer.class, rolledBackExecution[0]));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_request WHERE idempotency_key='rollback-key'",
                    Integer.class));
            assertEquals(0, outbox.count("PENDING"));
            var first = executions.insert(references[0], references[1], 1, "prompt-1", "model-1");
            var second = executions.insert(references[0], references[1], 1, "prompt-2", "model-1");
            UUID requestId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,
                    idempotency_key,fingerprint,requested_by,detection_result_version,prompt_version,
                    model_version,status,trace_id)
                    VALUES (?,?,?,'key-1',?,'synthetic-user',1,'prompt-1','model-1','PENDING','trace-1')
                    """, requestId, references[0], first.id(), "a".repeat(64));
            var event = AiReportExecutionCreated.newExecution(first.executionId(), requestId,
                    caseId, 1, "prompt-1", "model-1", "trace-1");
            transactions.executeWithoutResult(status -> {
                outbox.insert(event);
                status.setRollbackOnly();
            });
            assertEquals(0, outbox.count("PENDING"));
            transactions.executeWithoutResult(status -> outbox.insert(event));
            assertEquals(1, outbox.count("PENDING"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> transactions.executeWithoutResult(status -> outbox.insert(event)));
            assertTrue(executions.matches(event));

            var ready = new CountDownLatch(3);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(3);
            try {
                var targeted = pool.submit(() -> {
                    ready.countDown(); start.await();
                    return transactions.execute(status -> executions.claim(first.executionId(), 300));
                });
                var polling = pool.submit(() -> {
                    ready.countDown(); start.await();
                    return transactions.execute(status -> executions.claim(300));
                });
                var secondTargeted = pool.submit(() -> {
                    ready.countDown(); start.await();
                    return transactions.execute(status ->
                            executions.claim(first.executionId(), 300));
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                var a = targeted.get(10, TimeUnit.SECONDS);
                var b = polling.get(10, TimeUnit.SECONDS);
                var c = secondTargeted.get(10, TimeUnit.SECONDS);
                assertEquals(1, (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)
                        + (c.isPresent() ? 1 : 0));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*) FROM ai_report_execution WHERE status='GENERATING'
                        """, Integer.class));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*) FROM ai_report_execution WHERE status='PENDING'
                        """, Integer.class));
                assertTrue(transactions.execute(status ->
                        executions.claim(second.executionId(), 300)).isEmpty());
                long runningPk = a.or(() -> b).orElseGet(c::get).id();
                transactions.executeWithoutResult(status -> executions.complete(runningPk,
                        com.aifds.backend.aireport.entity.AiReportStatus.FAILED,
                        "WORKER_INTERRUPTED", null));
                assertTrue(transactions.execute(status -> executions.claim(300)).isPresent(),
                        "polling must reconcile the remaining PENDING execution");
            } finally {
                pool.shutdownNow();
            }
            var readyPublishers = new CountDownLatch(2);
            var startPublishers = new CountDownLatch(1);
            var publishers = Executors.newFixedThreadPool(2);
            OutboxRepository.Claim claim;
            try {
                var publisherA = publishers.submit(() -> {
                    readyPublishers.countDown(); startPublishers.await();
                    return transactions.execute(status -> outbox.claim(30));
                });
                var publisherB = publishers.submit(() -> {
                    readyPublishers.countDown(); startPublishers.await();
                    return transactions.execute(status -> outbox.claim(30));
                });
                assertTrue(readyPublishers.await(5, TimeUnit.SECONDS));
                startPublishers.countDown();
                var a = publisherA.get(10, TimeUnit.SECONDS);
                var b = publisherB.get(10, TimeUnit.SECONDS);
                assertEquals(1, (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0));
                claim = a.orElseGet(b::get);
            } finally {
                publishers.shutdownNow();
            }
            assertEquals(event.eventId(), claim.eventId());
            jdbc.update("UPDATE ai_report_outbox SET lease_until=now()-interval '1 second' WHERE id=?",
                    claim.id());
            var redelivery = transactions.execute(status -> outbox.claim(30)).orElseThrow();
            assertEquals(claim.eventId(), redelivery.eventId());
            assertNotEquals(claim.token(), redelivery.token());
            assertFalse(Boolean.TRUE.equals(transactions.execute(status -> outbox.published(claim))));
            assertTrue(Boolean.TRUE.equals(transactions.execute(status -> outbox.published(redelivery))));
            assertEquals(1, outbox.count("PUBLISHED"));
            assertEquals(0, outbox.count("PENDING"));
            UUID secondRequest = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,
                    idempotency_key,fingerprint,requested_by,detection_result_version,prompt_version,
                    model_version,status,trace_id)
                    VALUES (?,?,?,'key-2',?,'synthetic-user',1,'prompt-2','model-1',
                    'PENDING','trace-2')
                    """, secondRequest, references[0], second.id(), "b".repeat(64));
            var secondEvent = AiReportExecutionCreated.newExecution(second.executionId(),
                    secondRequest, caseId, 1, "prompt-2", "model-1", "trace-2");
            transactions.executeWithoutResult(status -> outbox.insert(secondEvent));
            jdbc.update("UPDATE ai_report_outbox SET attempt_count=9 WHERE event_id=?",
                    secondEvent.eventId());
            var last = transactions.execute(status -> outbox.claim(30)).orElseThrow();
            assertEquals(10, last.attemptCount());
            transactions.executeWithoutResult(status -> outbox.failed(last, "PUBLISH_FAILED"));
            assertEquals(1, outbox.count("BLOCKED"));
        }
    }

    long[] fixture(JdbcTemplate jdbc, UUID caseId) {
        Timestamp now = Timestamp.from(Instant.now());
        Long transaction = jdbc.queryForObject("""
                INSERT INTO financial_transaction(transaction_id,transaction_type,amount,
                currency_code,occurred_at,external_customer_ref,sender_account_ref,channel)
                VALUES (?,'ATM_WITHDRAWAL',100000,'KRW',?,'synthetic-customer',
                'synthetic-account','ATM') RETURNING id
                """, Long.class, UUID.randomUUID(), now);
        Long detection = jdbc.queryForObject("""
                INSERT INTO detection_result(detection_result_id,financial_transaction_id,
                detection_result_version,analysis_status,rule_set_version,scoring_policy_version,
                feature_version,evaluation_cutoff_at,analysis_trace_id)
                VALUES (?,?,1,'PENDING','rules-1','policy-1','features-1',?,'trace-fixture-001')
                RETURNING id
                """, Long.class, UUID.randomUUID(), transaction, now);
        Long casePk = jdbc.queryForObject("""
                INSERT INTO fraud_case(case_id,case_status,assignee_ref,review_started_at,
                created_at,last_changed_at) VALUES (?,'IN_REVIEW',?,?,?,?) RETURNING id
                """, Long.class, caseId, UUID.randomUUID().toString(), now, now, now);
        return new long[]{casePk, detection};
    }
}
