package com.aifds.backend.observability;

import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportKafkaMetrics {
    private final MeterRegistry registry;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final AiReportKafkaProperties properties;
    private final AdminClient admin;
    private final AtomicLong lag = new AtomicLong(-1);

    public AiReportKafkaMetrics(MeterRegistry registry, OutboxRepository outbox,
                                JdbcTemplate jdbc, AiReportKafkaProperties properties,
                                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.registry = registry;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.properties = properties;
        this.admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap,
                "request.timeout.ms", "3000", "default.api.timeout.ms", "3000"));
        for (String status : new String[]{"PENDING", "CLAIMED", "PUBLISHED", "BLOCKED"}) {
            Gauge.builder("finguardops.kafka.outbox.records", outbox,
                    value -> value.count(status)).tag("status", status).register(registry);
        }
        Gauge.builder("finguardops.kafka.outbox.oldest.seconds", outbox,
                OutboxRepository::oldestPendingSeconds).register(registry);
        Gauge.builder("finguardops.ai.report.pending.oldest.seconds", jdbc,
                value -> {
                    Long seconds = value.queryForObject("""
                            SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(created_at))::bigint,0)
                            FROM ai_report_execution WHERE status='PENDING'
                            """, Long.class);
                    return seconds == null ? 0 : seconds;
                }).register(registry);
        Gauge.builder("finguardops.kafka.consumer.lag", lag, AtomicLong::get)
                .tag("topic", properties.topic()).tag("group", properties.groupId())
                .register(registry);
        for (String action : new String[]{"QUARANTINE", "REPLAY"}) {
            Gauge.builder("finguardops.kafka.dlq.actions", jdbc, value ->
                    value.queryForObject("SELECT count(*) FROM ai_report_dlq_action WHERE action=?",
                            Long.class, action))
                    .tag("action", action).register(registry);
        }
        for (String status : new String[]{"PENDING", "CLAIMED", "ACKED", "BLOCKED", "SKIPPED"}) {
            Gauge.builder("finguardops.kafka.dlq.dispatch", jdbc, value ->
                    value.queryForObject("SELECT count(*) FROM ai_report_dlq_replay_dispatch WHERE status=?",
                            Long.class, status))
                    .tag("status", status).register(registry);
        }
    }

    public void started(String source) { increment("finguardops.ai.report.starts", "source", source); }
    public void published() { increment("finguardops.kafka.outbox.published", "result", "success"); }
    public void publishFailed() { increment("finguardops.kafka.outbox.published", "result", "failure"); }
    public void processed() { increment("finguardops.kafka.records", "result", "started"); }
    public void duplicate() { increment("finguardops.kafka.records", "result", "duplicate"); }
    public void busy() { increment("finguardops.kafka.records", "result", "busy"); }
    public void failed() { increment("finguardops.kafka.records", "result", "failure"); }
    public void reprocessed() { increment("finguardops.kafka.reprocess.attempts", "result", "retry"); }
    public void dlq() { increment("finguardops.kafka.dlq", "result", "published"); }
    public void dlqReplayPublished() { increment("finguardops.kafka.dlq.replay", "result", "published"); }
    public void dlqReplayUnconfirmed() { increment("finguardops.kafka.dlq.replay", "result", "unconfirmed"); }
    public void dlqReplaySkipped() { increment("finguardops.kafka.dlq.replay", "result", "skipped"); }

    private void increment(String name, String tag, String value) {
        Counter.builder(name).tag(tag, value).register(registry).increment();
    }

    @Scheduled(fixedDelay = 30000)
    public void refreshLag() {
        try {
            var committed = admin.listConsumerGroupOffsets(properties.groupId())
                    .partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            committed.keySet().stream().filter(partition ->
                    properties.topic().equals(partition.topic()))
                    .forEach(partition -> latest.put(partition, OffsetSpec.latest()));
            if (latest.isEmpty()) { lag.set(-1); return; }
            var ends = admin.listOffsets(latest).all().get(3, TimeUnit.SECONDS);
            long total = latest.keySet().stream().mapToLong(partition -> Math.max(0,
                    ends.get(partition).offset() - committed.get(partition).offset())).sum();
            lag.set(total);
        } catch (Exception exception) {
            lag.set(-1);
        }
    }

    @PreDestroy
    public void close() { admin.close(); }
}
