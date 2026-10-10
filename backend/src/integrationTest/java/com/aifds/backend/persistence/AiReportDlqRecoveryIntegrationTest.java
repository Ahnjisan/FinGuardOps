package com.aifds.backend.persistence;

import com.aifds.backend.aireport.dlq.AiReportDlqRepository;
import com.aifds.backend.aireport.dlq.AiReportDlqDispatcher;
import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.repository.AiReportRequestRepository;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.outbox.OutboxRepository;
import com.aifds.backend.outbox.OutboxRecoveryRepository;
import com.aifds.backend.observability.AiReportKafkaMetrics;
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
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class AiReportDlqRecoveryIntegrationTest {
    @Test
    void actionAndIntentAreAtomicAndOnlyOneOperatorCanApproveAnEvent() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            assertEquals(23, Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration").load().migrate().migrationsExecuted);
            var jdbc = new JdbcTemplate(source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var repository = new AiReportDlqRepository(jdbc);
            var codec = new AiReportExecutionCreatedCodec(new ObjectMapper().findAndRegisterModules());
            var outbox = new OutboxRepository(jdbc, codec);
            UUID caseId = UUID.randomUUID();
            long[] references = new AiReportOutboxIntegrationTest().fixture(jdbc, caseId);
            var executions = new AiReportExecutionRepository(jdbc);
            var requests = new AiReportRequestRepository(jdbc);
            UUID requestId = UUID.randomUUID();
            AiReportExecutionCreated event = tx.execute(ignored -> {
                var execution = executions.insert(references[0], references[1], 1,
                        "prompt-1", "model-1");
                requests.insert(requestId, references[0], execution.id(), null,
                        "dlq-key", "a".repeat(64), "synthetic-user", 1,
                        "prompt-1", "model-1", AiReportStatus.PENDING, false, false,
                        "trace-dlq");
                var created = AiReportExecutionCreated.newExecution(execution.executionId(),
                        requestId, caseId, 1, "prompt-1", "model-1", "trace-dlq");
                outbox.insert(created);
                return created;
            });
            assertNotNull(event);
            UUID topicId = UUID.randomUUID();
            assertThrows(IllegalStateException.class, () -> tx.execute(ignored -> {
                long action = repository.insertAction(topicId, 0, 3, "REPLAY",
                        "PRE_CLAIM_TRANSIENT", event.eventId(), event.executionId(),
                        UUID.randomUUID(), "trace-dlq");
                repository.insertIntent(action, event.eventId(), event.executionId());
                throw new IllegalStateException("before DB commit");
            }));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_dlq_action", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_report_dlq_replay_dispatch", Integer.class));

            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> approve(tx, repository, topicId, 3, event, ready, start));
                var second = pool.submit(() -> approve(tx, repository, topicId, 3, event, ready, start));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_report_dlq_action", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_report_dlq_replay_dispatch", Integer.class));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.update("DELETE FROM ai_report_dlq_action WHERE event_id=?", event.eventId()));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.execute(ignored -> {
                long action = repository.insertAction(topicId, 0, 4, "REPLAY",
                        "PRE_CLAIM_TRANSIENT", event.eventId(), event.executionId(),
                        UUID.randomUUID(), "trace-dlq");
                repository.insertIntent(action, event.eventId(), event.executionId());
                return action;
            }));
            jdbc.update("UPDATE ai_report_outbox SET status='PUBLISHED',published_at=now() "
                    + "WHERE event_id=?", event.eventId());
            @SuppressWarnings("unchecked")
            var kafka = (org.springframework.kafka.core.KafkaTemplate<String, String>)
                    mock(org.springframework.kafka.core.KafkaTemplate.class);
            var failedSend = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
            failedSend.completeExceptionally(new IllegalStateException("broker unavailable"));
            when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failedSend);
            var metrics = mock(AiReportKafkaMetrics.class);
            var dispatcher = new AiReportDlqDispatcher(repository, outbox, new OutboxRecoveryRepository(jdbc),
                    executions, codec, kafka, new AiReportKafkaProperties(true,
                    "source", "source.dlq", "worker", 1000, 30), metrics,
                    new DataSourceTransactionManager(source));
            dispatcher.dispatch();
            verify(metrics).dlqReplayUnconfirmed();
            assertEquals("PENDING", repository.action(topicId, 0, 3).orElseThrow().dispatchStatus());
            jdbc.update("UPDATE ai_report_dlq_replay_dispatch SET next_attempt_at=now() WHERE event_id=?",
                    event.eventId());
            var retried = tx.execute(ignored -> repository.claim().orElseThrow());
            assertEquals(2, retried.attemptCount());
            // Broker accepted the second send, then the process stopped before DB ack.
            jdbc.update("UPDATE ai_report_dlq_replay_dispatch SET lease_until=now()-interval '1 second' "
                    + "WHERE action_id=?", retried.actionId());
            var afterAckCrash = tx.execute(ignored -> repository.claim().orElseThrow());
            assertNotEquals(retried.token(), afterAckCrash.token());
            tx.executeWithoutResult(ignored -> repository.ack(afterAckCrash, 0, 11));
            assertEquals("ACKED", repository.action(topicId, 0, 3).orElseThrow().dispatchStatus());
            assertEquals(11L, repository.action(topicId, 0, 3).orElseThrow().ackOffset());
            assertEquals(3, jdbc.queryForObject("SELECT attempt_count FROM ai_report_dlq_replay_dispatch "
                    + "WHERE action_id=?", Integer.class, afterAckCrash.actionId()));

            UUID secondCaseId = UUID.randomUUID();
            long[] secondReferences = new AiReportOutboxIntegrationTest().fixture(jdbc, secondCaseId);
            UUID secondRequestId = UUID.randomUUID();
            var secondEvent = tx.execute(ignored -> {
                var execution = executions.insert(secondReferences[0], secondReferences[1], 1,
                        "prompt-1", "model-1");
                requests.insert(secondRequestId, secondReferences[0], execution.id(), null,
                        "dlq-second-key", "b".repeat(64), "synthetic-user", 1,
                        "prompt-1", "model-1", AiReportStatus.PENDING, false, false,
                        "trace-second");
                var created = AiReportExecutionCreated.newExecution(execution.executionId(),
                        secondRequestId, secondCaseId, 1, "prompt-1", "model-1", "trace-second");
                outbox.insert(created);
                return created;
            });
            assertNotNull(secondEvent);
            jdbc.update("UPDATE ai_report_outbox SET status='PUBLISHED',published_at=now() "
                    + "WHERE event_id=?", secondEvent.eventId());
            tx.executeWithoutResult(ignored -> {
                long id = repository.insertAction(topicId, 0, 5, "REPLAY", "PRE_CLAIM_TRANSIENT",
                        secondEvent.eventId(), secondEvent.executionId(), UUID.randomUUID(),
                        "trace-second");
                repository.insertIntent(id, secondEvent.eventId(), secondEvent.executionId());
            });
            jdbc.update("UPDATE ai_report_request SET status='GENERATING' WHERE ai_request_id=?",
                    secondRequestId);
            dispatcher.dispatch();
            assertEquals("SKIPPED", repository.action(topicId, 0, 5).orElseThrow().dispatchStatus());
            verify(metrics).dlqReplaySkipped();
            verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        }
    }

    private int approve(TransactionTemplate tx, AiReportDlqRepository repository, UUID topicId,
                        long offset, AiReportExecutionCreated event,
                        CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            tx.executeWithoutResult(ignored -> {
                long id = repository.insertAction(topicId, 0, offset, "REPLAY",
                        "PRE_CLAIM_TRANSIENT", event.eventId(), event.executionId(),
                        UUID.randomUUID(), "trace-dlq");
                repository.insertIntent(id, event.eventId(), event.executionId());
            });
            return 1;
        } catch (org.springframework.dao.DataAccessException conflict) {
            return 0;
        }
    }
}
