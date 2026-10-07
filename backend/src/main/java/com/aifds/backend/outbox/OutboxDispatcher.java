package com.aifds.backend.outbox;

import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import com.aifds.backend.observability.LocalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class OutboxDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final AiReportKafkaProperties properties;
    private final AiReportKafkaMetrics metrics;
    private final TransactionTemplate transactions;

    public OutboxDispatcher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                            AiReportKafkaProperties properties, AiReportKafkaMetrics metrics,
                            PlatformTransactionManager manager) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${finguardops.kafka.outbox-poll-ms:1000}")
    public void dispatch() {
        transactions.executeWithoutResult(ignored -> outbox.blockExpiredExhausted());
        OutboxRepository.Claim claim = transactions.execute(ignored ->
                outbox.claim(properties.outboxLeaseSeconds()).orElse(null));
        if (claim == null) return;
        try (LocalTrace traceScope = LocalTrace.execution("ai-report.outbox-publish", claim.executionId())) {
            try {
                kafka.send(properties.topic(), claim.executionId().toString(), claim.payload())
                        .get(10, TimeUnit.SECONDS);
                Boolean marked = transactions.execute(ignored -> outbox.published(claim));
                if (Boolean.TRUE.equals(marked)) {
                    metrics.published();
                    LOGGER.info("event=ai_report_outbox_published executionId={} otelTraceId={}",
                            claim.executionId(), LocalTrace.currentTraceId());
                } else metrics.publishFailed();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                transactions.executeWithoutResult(ignored -> outbox.failed(claim, "PUBLISH_INTERRUPTED"));
                metrics.publishFailed();
                LOGGER.warn("event=ai_report_outbox_failed executionId={} code=PUBLISH_INTERRUPTED",
                        claim.executionId());
            } catch (Exception exception) {
                transactions.executeWithoutResult(ignored -> outbox.failed(claim, "PUBLISH_FAILED"));
                metrics.publishFailed();
                LOGGER.warn("event=ai_report_outbox_failed executionId={} code=PUBLISH_FAILED",
                        claim.executionId());
            }
        }
    }
}
