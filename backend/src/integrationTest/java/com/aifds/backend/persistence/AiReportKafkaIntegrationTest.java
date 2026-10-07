package com.aifds.backend.persistence;

import com.aifds.backend.aireport.client.AiReportHttpClient;
import com.aifds.backend.aireport.config.AiReportKafkaConfiguration;
import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.aireport.config.AiReportProperties;
import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.event.AiReportExecutionConsumer;
import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.repository.AiReportRepository;
import com.aifds.backend.aireport.repository.AiReportRequestRepository;
import com.aifds.backend.aireport.repository.ProviderCallAttemptRepository;
import com.aifds.backend.aireport.service.AiReportInputProjection;
import com.aifds.backend.aireport.service.AiReportService;
import com.aifds.backend.aireport.service.AiReportWorker;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.security.principal.CurrentAuditActorProvider;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import com.aifds.backend.outbox.OutboxDispatcher;
import com.aifds.backend.outbox.OutboxRepository;
import com.aifds.backend.outbox.OutboxRecoveryRepository;
import com.aifds.backend.outbox.OutboxRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiReportKafkaIntegrationTest {
    private static final String IMAGE = "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    private static final String TOPIC = "finguardops.ai-report-execution-created.v1";
    private static final String DLQ = TOPIC + ".dlq";

    @Test
    void realBrokerAndDatabaseDeliverOnlyOneReportAndQuarantinePoison() throws Exception {
        int port = freePort();
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine");
             var broker = new LocalBroker(port)) {
            postgres.start();
            broker.start();
            String bootstrap = "localhost:" + port;
            try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1),
                        new NewTopic(DLQ, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
                assertEquals(1, admin.describeTopics(List.of(TOPIC)).allTopicNames()
                        .get(10, TimeUnit.SECONDS).get(TOPIC).partitions().size());
            }
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            assertEquals(19, Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration").load().migrate().migrationsExecuted);
            var jdbc = new JdbcTemplate(source);
            var manager = new DataSourceTransactionManager(source);
            var transactions = new TransactionTemplate(manager);
            var json = new ObjectMapper().findAndRegisterModules();
            var codec = new AiReportExecutionCreatedCodec(json);
            var executions = new AiReportExecutionRepository(jdbc);
            var requests = new AiReportRequestRepository(jdbc);
            var reports = new AiReportRepository(jdbc, json);
            var attempts = new ProviderCallAttemptRepository(jdbc);
            var outbox = new OutboxRepository(jdbc, codec);
            UUID caseId = UUID.randomUUID();
            long[] references = new AiReportOutboxIntegrationTest().fixture(jdbc, caseId);
            UUID requestId = UUID.randomUUID();
            var created = transactions.execute(status -> {
                var execution = executions.insert(references[0], references[1], 1,
                        "prompt-1", "model-1");
                requests.insert(requestId, references[0], execution.id(), null,
                        "integration-key", "a".repeat(64), "synthetic-analyst", 1,
                        "prompt-1", "model-1", AiReportStatus.PENDING, false, false,
                        "trace-integration");
                var event = AiReportExecutionCreated.newExecution(execution.executionId(),
                        requestId, caseId, 1, "prompt-1", "model-1", "trace-integration");
                outbox.insert(event);
                return event;
            });
            assertNotNull(created);
            assertEquals(1, outbox.count("PENDING"));
            assertTrue(executions.matches(created));

            var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.ACKS_CONFIG, "all"));
            var kafka = new KafkaTemplate<>(producerFactory);
            try (var receiver = receiver(bootstrap, TOPIC)) {
                var properties = new AiReportKafkaProperties(true, TOPIC, DLQ,
                        "finguardops-ai-report-worker-v1", 1000, 30);
                var meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                var metrics = new AiReportKafkaMetrics(meterRegistry, outbox, jdbc,
                        properties, bootstrap);
                var dispatcher = new OutboxDispatcher(outbox, kafka, properties, metrics, manager);
                dispatcher.dispatch();
                assertEquals(1, outbox.count("PUBLISHED"));
                ConsumerRecord<String, String> record = awaitRecord(receiver);
                assertEquals(created.executionId().toString(), record.key());
                assertEquals(created.eventId(), codec.decode(record.value(), record.key()).eventId());

                FraudCase fraudCase = mock(FraudCase.class);
                var cases = mock(FraudCaseRepository.class);
                when(cases.findById(references[0])).thenReturn(Optional.of(fraudCase));
                when(cases.findByCaseId(caseId)).thenReturn(Optional.of(fraudCase));
                when(fraudCase.getId()).thenReturn(references[0]);
                var projection = mock(AiReportInputProjection.class);
                var input = new AiReportDtos.GenerationRequest(caseId, 1, "HIGH", 80,
                        "rules-1", List.of(new AiReportDtos.RuleEvidence("RULE_A", "1",
                        "REASON_A", 20)), "trace-integration");
                when(projection.project(fraudCase, 1, "trace-integration"))
                        .thenReturn(new AiReportInputProjection.Projection(references[1], input));
                var client = mock(AiReportHttpClient.class);
                when(client.generate(input)).thenReturn(new AiReportDtos.GenerationResult(
                        "FALLBACK_COMPLETED", "TEMPLATE_FALLBACK",
                        new AiReportDtos.Content("Synthetic report",
                                List.of(new AiReportDtos.KeyReason("REASON_A", "Synthetic reason")),
                                List.of("Synthetic check")), null, "LLM_TIMEOUT", "model-1",
                        "prompt-1", List.of(new AiReportDtos.Attempt("OLLAMA_LOCAL", null,
                                null, null, null, 45000, "TIMEOUT"))));
                var worker = new AiReportWorker(provider(manager), provider(jdbc),
                        new AiReportProperties.Values("http://localhost:8000", 30000, 300),
                        provider(executions), provider(requests), provider(reports),
                        provider(attempts), provider(cases), provider(projection),
                        provider(client), provider(metrics));
                var consumer = new AiReportExecutionConsumer(codec, executions, worker, metrics);
                Acknowledgment ack = mock(Acknowledgment.class);
                consumer.consume(record.value(), record.key(), ack);
                receiver.commitSync();
                metrics.refreshLag();
                assertEquals(0, meterRegistry.get("finguardops.kafka.consumer.lag")
                        .tag("topic", TOPIC).tag("group", properties.groupId())
                        .gauge().value());
                assertEquals(1, meterRegistry.get("finguardops.kafka.outbox.records")
                        .tag("status", "PUBLISHED").gauge().value());
                assertEquals(1, meterRegistry.get("finguardops.ai.report.starts")
                        .tag("source", "kafka").counter().count());
                String metricsOutput = meterRegistry.scrape();
                assertTrue(metricsOutput.contains("finguardops_kafka_outbox_records"));
                assertTrue(metricsOutput.contains("finguardops_kafka_consumer_lag"));
                assertTrue(metricsOutput.contains("finguardops_ai_report_pending_oldest_seconds"));
                assertTrue(metricsOutput.contains("finguardops_ai_report_starts_total"));
                assertFalse(metricsOutput.contains(created.executionId().toString()));
                assertFalse(metricsOutput.contains(created.eventId().toString()));
                verify(ack).acknowledge();
                verify(client, times(1)).generate(input);
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_report", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM provider_call_attempt",
                        Integer.class));
                assertEquals("FALLBACK_COMPLETED", executions.status(created.executionId())
                        .orElseThrow().name());
                var service = new AiReportService(cases, jdbc, null, null, requests,
                        executions, reports, null, null, null, outbox);
                assertNotNull(service.current(caseId, "trace-integration").currentReport());
                assertEquals(requestId, service.current(caseId,
                        "trace-integration").currentReport().initiatingAiRequestId());

                consumer.consume(record.value(), record.key(), ack);
                verify(client, times(1)).generate(input);
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_report", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM provider_call_attempt",
                        Integer.class));
                assertThrows(AiReportExecutionCreatedCodec.InvalidEventException.class,
                        () -> consumer.consume(record.value(), UUID.randomUUID().toString(), ack));
                verify(client, times(1)).generate(input);
                assertEquals(1, outbox.count("PUBLISHED"));

                var errorHandler = new AiReportKafkaConfiguration()
                        .aiReportKafkaErrorHandler(kafka, properties, metrics);
                try (var deadLetters = receiver(bootstrap, DLQ)) {
                    var absent = AiReportExecutionCreated.newExecution(UUID.randomUUID(),
                            UUID.randomUUID(), UUID.randomUUID(), 1, "prompt-1", "model-1",
                            "trace-absent");
                    List<Map.Entry<String, String>> invalid = List.of(
                            Map.entry(UUID.randomUUID().toString(), record.value()),
                            Map.entry(record.key(), record.value().replaceAll(
                                    "\"eventVersion\"\\s*:\\s*1", "\"eventVersion\":2")),
                            Map.entry(record.key(), "{" + "x".repeat(4096) + "}"),
                            Map.entry(absent.executionId().toString(), codec.encode(absent)));
                    for (int scenario = 0; scenario < invalid.size(); scenario++) {
                        var sample = invalid.get(scenario);
                        kafka.send(TOPIC, sample.getKey(), sample.getValue())
                                .get(10, TimeUnit.SECONDS);
                        var poison = awaitRecord(receiver);
                        assertEquals(sample.getKey(), poison.key());
                        assertEquals(sample.getValue(), poison.value());
                        var failure = assertThrows(
                                AiReportExecutionCreatedCodec.InvalidEventException.class,
                                () -> consumer.consume(poison.value(), poison.key(), ack),
                                "poison scenario " + scenario);
                        assertTrue(errorHandler.handleOne(failure, poison,
                                mock(org.apache.kafka.clients.consumer.Consumer.class),
                                mock(org.springframework.kafka.listener.MessageListenerContainer.class)));
                        var quarantined = awaitRecord(deadLetters);
                        assertEquals(poison.key(), quarantined.key());
                        assertEquals(poison.value(), quarantined.value());
                        receiver.commitSync();
                    }
                }
                verify(client, times(1)).generate(input);
                kafka.send(TOPIC, record.key(), record.value()).get(10, TimeUnit.SECONDS);
                var manualReplay = awaitRecord(receiver);
                consumer.consume(manualReplay.value(), manualReplay.key(), ack);
                verify(client, times(1)).generate(input);

                UUID secondCaseId = UUID.randomUUID();
                long[] secondReferences = new AiReportOutboxIntegrationTest().fixture(jdbc,
                        secondCaseId);
                FraudCase secondCase = mock(FraudCase.class);
                when(cases.findById(secondReferences[0])).thenReturn(Optional.of(secondCase));
                when(cases.findByCaseId(secondCaseId)).thenReturn(Optional.of(secondCase));
                when(secondCase.getId()).thenReturn(secondReferences[0]);
                when(secondCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
                var secondInput = new AiReportDtos.GenerationRequest(secondCaseId, 1, "HIGH",
                        80, "rules-1", List.of(new AiReportDtos.RuleEvidence("RULE_A", "1",
                        "REASON_A", 20)), "trace-integration-2");
                when(projection.project(secondCase, 1, "trace-integration-2"))
                        .thenReturn(new AiReportInputProjection.Projection(secondReferences[1],
                                secondInput));
                when(client.identity()).thenReturn(new AiReportDtos.ModelIdentity("model-1",
                        "prompt-2"));
                var actors = mock(CurrentAuditActorProvider.class);
                when(actors.currentUserSubject()).thenReturn(UUID.randomUUID());
                var createService = new AiReportService(cases, jdbc, projection, client, requests,
                        executions, reports, new IdempotencyKeyValidator(), actors,
                        mock(EntityManager.class), outbox);
                broker.stop();
                var accepted = transactions.execute(status -> createService.create(secondCaseId,
                        "integration-key-2", new AiReportDtos.CreateRequest(1, null),
                        "trace-integration-2"));
                assertNotNull(accepted);
                assertTrue(accepted.accepted());
                assertEquals("PENDING", accepted.response().reportStatus());
                var replay = transactions.execute(status -> createService.create(secondCaseId,
                        "integration-key-2", new AiReportDtos.CreateRequest(1, null),
                        "trace-integration-2"));
                var shared = transactions.execute(status -> createService.create(secondCaseId,
                        "integration-shared-key", new AiReportDtos.CreateRequest(1, null),
                        "trace-integration-2"));
                assertNotNull(replay);
                assertNotNull(shared);
                assertTrue(shared.response().executionShared());
                assertEquals(1, outbox.count("PENDING"));
                UUID secondExecutionId = jdbc.queryForObject("""
                        SELECT execution_id FROM ai_report_execution WHERE fraud_case_id=?
                        """, UUID.class, secondReferences[0]);
                String secondPayload = jdbc.queryForObject("""
                        SELECT payload::text FROM ai_report_outbox WHERE execution_id=?
                        """, String.class, secondExecutionId);
                var second = codec.decode(secondPayload, secondExecutionId.toString());
                assertFalse(errorHandler.handleOne(
                        new AiReportExecutionCreatedCodec.InvalidEventException("partition key"),
                        new ConsumerRecord<>(TOPIC, 0, 101L, "bad-key", record.value()),
                        mock(org.apache.kafka.clients.consumer.Consumer.class),
                        mock(org.springframework.kafka.listener.MessageListenerContainer.class)));
                dispatcher.dispatch();
                assertEquals(1, outbox.count("PENDING"));
                assertEquals(AiReportStatus.PENDING,
                        executions.status(second.executionId()).orElseThrow());
                verify(client, times(1)).generate(input);
                jdbc.update("""
                        UPDATE ai_report_outbox SET status='BLOCKED',attempt_count=10,
                            next_attempt_at=now(),last_failure_code='PUBLISH_ACK_UNCONFIRMED'
                        WHERE event_id=?
                        """, second.eventId());
                var recoveryService = new OutboxRecoveryService(outbox,
                        new OutboxRecoveryRepository(jdbc), codec);
                assertTrue(recoveryService.inspect(second.executionId(),
                        "trace-integration-2").requeueAllowed());
                assertEquals("PENDING", transactions.execute(status -> recoveryService.requeue(
                        second.eventId(), second.executionId(), "BLOCKED", UUID.randomUUID(),
                        "trace-integration-2")).outboxStatus());
                assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*) FROM ai_report_outbox_requeue_log WHERE event_id=?
                        """, Integer.class, second.eventId()));
                broker.start();
                try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
                    admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1),
                            new NewTopic(DLQ, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
                }
                when(client.generate(secondInput)).thenReturn(new AiReportDtos.GenerationResult(
                        "FALLBACK_COMPLETED", "TEMPLATE_FALLBACK",
                        new AiReportDtos.Content("Second synthetic report",
                                List.of(new AiReportDtos.KeyReason("REASON_A", "Synthetic reason")),
                                List.of("Synthetic check")), null, "LLM_TIMEOUT", "model-1",
                        "prompt-2", List.of(new AiReportDtos.Attempt("OLLAMA_LOCAL", null,
                                null, null, null, 45000, "TIMEOUT"))));
                Thread.sleep(1200);
                try (var recovered = receiver(bootstrap, TOPIC)) {
                    dispatcher.dispatch();
                    assertEquals(2, outbox.count("PUBLISHED"));
                    var resumed = awaitRecord(recovered);
                    assertEquals(second.executionId().toString(), resumed.key());
                    consumer.consume(resumed.value(), resumed.key(), mock(Acknowledgment.class));
                }
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai_report",
                        Integer.class));
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM provider_call_attempt",
                        Integer.class));
                verify(client, times(1)).generate(secondInput);
                var cached = transactions.execute(status -> createService.create(secondCaseId,
                        "integration-cache-key", new AiReportDtos.CreateRequest(1, null),
                        "trace-integration-2"));
                assertNotNull(cached);
                assertTrue(cached.response().cacheHit());
                assertEquals(2, outbox.count("PUBLISHED"));

                var listenerProperties = new ContainerProperties(TOPIC);
                listenerProperties.setGroupId("integration-listener-" + UUID.randomUUID());
                listenerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
                listenerProperties.setMessageListener(
                        (AcknowledgingMessageListener<String, String>) (message, acknowledgment) ->
                                consumer.consume(message.value(), message.key(), acknowledgment));
                var consumerFactory = new DefaultKafkaConsumerFactory<String, String>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
                var listener = new ConcurrentMessageListenerContainer<>(consumerFactory,
                        listenerProperties);
                listener.setCommonErrorHandler(errorHandler);
                listener.start();
                waitForAssignment(listener);
                listener.stop();
                UUID thirdCaseId = UUID.randomUUID();
                long[] thirdReferences = new AiReportOutboxIntegrationTest().fixture(jdbc,
                        thirdCaseId);
                FraudCase thirdCase = mock(FraudCase.class);
                when(cases.findByCaseId(thirdCaseId)).thenReturn(Optional.of(thirdCase));
                when(cases.findById(thirdReferences[0])).thenReturn(Optional.of(thirdCase));
                when(thirdCase.getId()).thenReturn(thirdReferences[0]);
                when(thirdCase.getCaseStatus()).thenReturn(FraudCaseStatus.IN_REVIEW);
                var thirdInput = new AiReportDtos.GenerationRequest(thirdCaseId, 1, "HIGH",
                        80, "rules-1", List.of(new AiReportDtos.RuleEvidence("RULE_A", "1",
                        "REASON_A", 20)), "trace-integration-3");
                when(projection.project(thirdCase, 1, "trace-integration-3"))
                        .thenReturn(new AiReportInputProjection.Projection(thirdReferences[1],
                                thirdInput));
                when(client.identity()).thenReturn(new AiReportDtos.ModelIdentity("model-1",
                        "prompt-3"));
                when(client.generate(thirdInput)).thenReturn(new AiReportDtos.GenerationResult(
                        "FALLBACK_COMPLETED", "TEMPLATE_FALLBACK",
                        new AiReportDtos.Content("Third synthetic report",
                                List.of(new AiReportDtos.KeyReason("REASON_A", "Synthetic reason")),
                                List.of("Synthetic check")), null, "LLM_TIMEOUT", "model-1",
                        "prompt-3", List.of(new AiReportDtos.Attempt("OLLAMA_LOCAL", null,
                                null, null, null, 45000, "TIMEOUT"))));
                var thirdAccepted = transactions.execute(status -> createService.create(thirdCaseId,
                        "integration-key-3", new AiReportDtos.CreateRequest(1, null),
                        "trace-integration-3"));
                assertNotNull(thirdAccepted);
                assertTrue(thirdAccepted.accepted());
                var failedMark = spy(outbox);
                doThrow(new IllegalStateException("database mark unavailable"))
                        .when(failedMark).published(any());
                new OutboxDispatcher(failedMark, kafka, properties, metrics, manager).dispatch();
                assertEquals(1, outbox.count("PENDING"));
                Thread.sleep(1200);
                dispatcher.dispatch();
                assertEquals(3, outbox.count("PUBLISHED"));
                listener.start();
                waitForReportCount(jdbc, 3);
                listener.stop();
                assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM provider_call_attempt",
                        Integer.class));
                verify(client, times(1)).generate(thirdInput);
                metrics.close();
                meterRegistry.close();
            } finally {
                producerFactory.destroy();
            }
        }
    }

    private KafkaConsumer<String, String> receiver(String bootstrap, String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, topic.equals(TOPIC)
                        ? "finguardops-ai-report-worker-v1" : "integration-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) return records.iterator().next();
        }
        throw new AssertionError("Kafka record was not delivered");
    }

    private void waitForAssignment(ConcurrentMessageListenerContainer<String, String> listener)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (listener.getAssignedPartitions() != null
                    && !listener.getAssignedPartitions().isEmpty()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka listener did not receive a partition");
    }

    private void waitForReportCount(JdbcTemplate jdbc, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM ai_report", Integer.class) == expected)
                return;
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka listener did not finish the report");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> result = mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(bean);
        return result;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class LocalBroker extends GenericContainer<LocalBroker> {
        LocalBroker(int hostPort) {
            super(IMAGE);
            addFixedExposedPort(hostPort, 9092);
            withEnv("KAFKA_NODE_ID", "1");
            withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
            withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093");
            withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093");
            withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + hostPort);
            withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                    "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT");
            withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT");
            withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
            withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
            withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
            withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
            withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
            withEnv("CLUSTER_ID", "4L6g3nShT-eMCtK--X86sw");
            waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        }
    }
}
