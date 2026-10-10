package com.aifds.backend.persistence;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.outbox.OutboxRecoveryRepository;
import com.aifds.backend.outbox.OutboxRecoveryService;
import com.aifds.backend.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OutboxRecoveryIntegrationTest {
    @Test
    void requeuesOneBlockedEventOnceAndKeepsLogAppendOnly() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            assertEquals(22, Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration").load().migrate().migrationsExecuted);
            var jdbc = new JdbcTemplate(source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var codec = new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules());
            var outbox = new OutboxRepository(jdbc, codec);
            var service = new OutboxRecoveryService(outbox, new OutboxRecoveryRepository(jdbc), codec);
            var event = seed(jdbc, outbox, "one");
            assertTrue(service.inspect(event.executionId(), "trace-query-001").requeueAllowed());
            assertEquals(409, assertThrows(AiReportException.class,
                    () -> tx.execute(status -> service.requeue(event.eventId(), UUID.randomUUID(),
                            "BLOCKED", UUID.randomUUID(), "trace-query-001"))).status().value());
            assertEquals(409, assertThrows(AiReportException.class,
                    () -> tx.execute(status -> service.requeue(event.eventId(), event.executionId(),
                            "PENDING", UUID.randomUUID(), "trace-query-001"))).status().value());
            assertEquals("BLOCKED", outbox.recoveryRow(event.eventId(), false).orElseThrow().status());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_outbox_requeue_log WHERE event_id=?",
                    Integer.class, event.eventId()));
            jdbc.execute("""
                    ALTER TABLE ai_report_outbox_requeue_log ADD CONSTRAINT ck_test_reject_record
                    CHECK (trace_id <> 'trace-reject-001')
                    """);
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> tx.execute(status -> service.requeue(event.eventId(), event.executionId(),
                            "BLOCKED", UUID.randomUUID(), "trace-reject-001")));
            assertEquals("BLOCKED", outbox.recoveryRow(event.eventId(), false).orElseThrow().status());
            assertEquals(10, outbox.recoveryRow(event.eventId(), false).orElseThrow().attemptCount());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_outbox_requeue_log WHERE event_id=?",
                    Integer.class, event.eventId()));
            jdbc.execute("ALTER TABLE ai_report_outbox_requeue_log DROP CONSTRAINT ck_test_reject_record");
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> attempt(tx, service, event, ready, start));
                var second = pool.submit(() -> attempt(tx, service, event, ready, start));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
            assertEquals("PENDING", outbox.recoveryRow(event.eventId(), false).orElseThrow().status());
            assertEquals(0, outbox.recoveryRow(event.eventId(), false).orElseThrow().attemptCount());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_report_outbox_requeue_log WHERE event_id=?",
                    Integer.class, event.eventId()));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.update("DELETE FROM ai_report_outbox_requeue_log WHERE event_id=?",
                            event.eventId()));
            assertEquals(409, assertThrows(AiReportException.class,
                    () -> tx.execute(status -> service.requeue(event.eventId(), event.executionId(),
                            "BLOCKED", UUID.randomUUID(), "trace-query-001"))).status().value());
        }
    }

    @Test
    void refusesInvalidRelationshipsTerminalWorkerAndSharedRequest() {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var codec = new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules());
            var outbox = new OutboxRepository(jdbc, codec);
            var service = new OutboxRecoveryService(outbox, new OutboxRecoveryRepository(jdbc), codec);
            var poison = seed(jdbc, outbox, "poison");
            jdbc.update("UPDATE ai_report_outbox SET payload=jsonb_set(payload,'{eventVersion}','2'::jsonb) WHERE event_id=?", poison.eventId());
            assertEquals("EVENT_INVALID", service.inspect(poison.executionId(), "trace-query-001").rejectionReason());
            var changed = seed(jdbc, outbox, "changed");
            jdbc.update("UPDATE ai_report_request SET status='GENERATING' WHERE ai_request_id=?",
                    changed.initiatingAiRequestId());
            assertEquals("REQUEST_MISMATCH", service.inspect(changed.executionId(), "trace-query-001").rejectionReason());
            var claimed = seed(jdbc, outbox, "claimed");
            jdbc.update("""
                    UPDATE ai_report_outbox SET status='CLAIMED',claim_token=?,
                        lease_until=now()+interval '60 seconds',attempt_count=1
                    WHERE event_id=?
                    """, UUID.randomUUID(), claimed.eventId());
            assertEquals("OUTBOX_CLAIMED", service.inspect(claimed.executionId(),
                    "trace-query-001").rejectionReason());
            assertEquals(409, assertThrows(AiReportException.class,
                    () -> service.requeue(claimed.eventId(), claimed.executionId(),
                            "BLOCKED", UUID.randomUUID(), "trace-query-001"))
                    .status().value());
            var interrupted = seed(jdbc, outbox, "interrupted");
            jdbc.update("UPDATE ai_report_execution SET status='FAILED',failure_code='WORKER_INTERRUPTED' WHERE execution_id=?",
                    interrupted.executionId());
            assertEquals("EXECUTION_NOT_PENDING", service.inspect(interrupted.executionId(), "trace-query-001").rejectionReason());
            var generating = seed(jdbc, outbox, "generating");
            jdbc.update("UPDATE ai_report_execution SET status='GENERATING' WHERE execution_id=?",
                    generating.executionId());
            assertEquals("EXECUTION_NOT_PENDING", service.inspect(generating.executionId(), "trace-query-001").rejectionReason());
            var relationship = seed(jdbc, outbox, "relationship");
            jdbc.update("""
                    UPDATE ai_report_outbox SET payload=jsonb_set(payload,'{modelVersion}',
                        '"other-model"'::jsonb) WHERE event_id=?
                    """, relationship.eventId());
            assertEquals("EVENT_RELATIONSHIP_MISMATCH", service.inspect(relationship.executionId(),
                    "trace-query-001").rejectionReason());
            var wrongCase = seed(jdbc, outbox, "wrong-case");
            long otherCasePk = new AiReportOutboxIntegrationTest().fixture(jdbc, UUID.randomUUID())[0];
            jdbc.update("UPDATE ai_report_request SET fraud_case_id=? WHERE ai_request_id=?",
                    otherCasePk, wrongCase.initiatingAiRequestId());
            assertEquals("REQUEST_MISMATCH", service.inspect(wrongCase.executionId(),
                    "trace-query-001").rejectionReason());
            assertEquals(409, assertThrows(AiReportException.class,
                    () -> service.requeue(wrongCase.eventId(), wrongCase.executionId(),
                            "BLOCKED", UUID.randomUUID(), "trace-query-001"))
                    .status().value());
            assertEquals("BLOCKED", outbox.recoveryRow(wrongCase.eventId(), false).orElseThrow().status());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_outbox_requeue_log WHERE event_id=?",
                    Integer.class, wrongCase.eventId()));
            var attempted = seed(jdbc, outbox, "attempted");
            jdbc.update("""
                    INSERT INTO provider_call_attempt(execution_id,attempt_number,provider,
                        latency_ms,outcome) SELECT id,1,'OLLAMA_LOCAL',0,'TIMEOUT'
                        FROM ai_report_execution WHERE execution_id=?
                    """, attempted.executionId());
            assertEquals("ATTEMPT_EXISTS", service.inspect(attempted.executionId(),
                    "trace-query-001").rejectionReason());
            var completed = seed(jdbc, outbox, "completed");
            jdbc.update("UPDATE ai_report_execution SET status='COMPLETED' WHERE execution_id=?",
                    completed.executionId());
            assertEquals("EXECUTION_NOT_PENDING", service.inspect(completed.executionId(),
                    "trace-query-001").rejectionReason());
            var reported = seed(jdbc, outbox, "reported");
            jdbc.update("""
                    INSERT INTO ai_report(report_id,fraud_case_id,execution_id,
                        detection_result_version,prompt_version,model_version,report_status,
                        report_source,summary,key_reasons,timeline_summary,
                        investigation_checklist,trace_id)
                    SELECT ?,e.fraud_case_id,e.id,1,e.prompt_version,e.model_version,
                        'COMPLETED','LLM','Synthetic','[]'::jsonb,'Synthetic',
                        '[]'::jsonb,'trace-fixture-001'
                    FROM ai_report_execution e WHERE e.execution_id=?
                    """, UUID.randomUUID(), reported.executionId());
            assertEquals("REPORT_EXISTS", service.inspect(reported.executionId(),
                    "trace-query-001").rejectionReason());
        }
    }

    private int attempt(TransactionTemplate tx, OutboxRecoveryService service,
                        AiReportExecutionCreated event, CountDownLatch ready,
                        CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        try {
            tx.execute(status -> service.requeue(event.eventId(), event.executionId(),
                    "BLOCKED", UUID.randomUUID(), "trace-query-001"));
            return 1;
        } catch (AiReportException | org.springframework.dao.DataAccessException expected) {
            return 0;
        }
    }

    private AiReportExecutionCreated seed(JdbcTemplate jdbc, OutboxRepository outbox, String key) {
        UUID caseId = UUID.randomUUID();
        long[] references = new AiReportOutboxIntegrationTest().fixture(jdbc, caseId);
        var execution = new AiReportExecutionRepository(jdbc).insert(references[0], references[1],
                1, "prompt-" + key, "model-1");
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,
                    idempotency_key,fingerprint,requested_by,detection_result_version,
                    prompt_version,model_version,status,trace_id)
                VALUES (?,?,?,? ,?,'synthetic-user',1,?,'model-1','PENDING','trace-fixture-001')
                """, requestId, references[0], execution.id(), "key-" + key,
                "a".repeat(64), "prompt-" + key);
        var event = AiReportExecutionCreated.newExecution(execution.executionId(), requestId,
                caseId, 1, "prompt-" + key, "model-1", "trace-fixture-001");
        outbox.insert(event);
        jdbc.update("UPDATE ai_report_outbox SET status='BLOCKED',attempt_count=10,"
                + "last_failure_code='PUBLISH_ACK_UNCONFIRMED' WHERE event_id=?", event.eventId());
        return event;
    }
}
