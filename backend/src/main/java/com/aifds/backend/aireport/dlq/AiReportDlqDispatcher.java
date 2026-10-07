package com.aifds.backend.aireport.dlq;

import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import com.aifds.backend.outbox.OutboxRecoveryRepository;
import com.aifds.backend.outbox.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportDlqDispatcher {
    private final AiReportDlqRepository intents;
    private final OutboxRepository outbox;
    private final OutboxRecoveryRepository recovery;
    private final AiReportExecutionRepository executions;
    private final AiReportExecutionCreatedCodec codec;
    private final KafkaTemplate<String, String> kafka;
    private final AiReportKafkaProperties properties;
    private final AiReportKafkaMetrics metrics;
    private final TransactionTemplate transactions;

    public AiReportDlqDispatcher(AiReportDlqRepository intents, OutboxRepository outbox,
                                 OutboxRecoveryRepository recovery,
                                 AiReportExecutionRepository executions,
                                 AiReportExecutionCreatedCodec codec,
                                 KafkaTemplate<String, String> kafka,
                                 AiReportKafkaProperties properties,
                                 AiReportKafkaMetrics metrics,
                                 PlatformTransactionManager manager) {
        this.intents = intents;
        this.outbox = outbox;
        this.recovery = recovery;
        this.executions = executions;
        this.codec = codec;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${finguardops.kafka.outbox-poll-ms:1000}")
    public void dispatch() {
        transactions.executeWithoutResult(ignored -> intents.blockExpiredExhausted());
        var claim = transactions.execute(ignored -> intents.claim().orElse(null));
        if (claim == null) return;
        String canonical = transactions.execute(ignored -> canonicalIfPending(claim));
        if (canonical == null) {
            transactions.executeWithoutResult(ignored -> intents.skip(claim));
            metrics.dlqReplaySkipped();
            return;
        }
        try {
            var result = kafka.send(properties.topic(), claim.executionId().toString(), canonical)
                    .get(10, TimeUnit.SECONDS);
            transactions.executeWithoutResult(ignored -> intents.ack(claim,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset()));
            metrics.dlqReplayPublished();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            transactions.executeWithoutResult(ignored -> intents.fail(claim));
            metrics.dlqReplayUnconfirmed();
        } catch (Exception unconfirmed) {
            transactions.executeWithoutResult(ignored -> intents.fail(claim));
            metrics.dlqReplayUnconfirmed();
        }
    }

    private String canonicalIfPending(AiReportDlqRepository.DispatchRow claim) {
        var row = outbox.recoveryRow(claim.eventId(), true).orElse(null);
        if (row == null || !"PUBLISHED".equals(row.status())
                || !claim.executionId().equals(row.executionId())) return null;
        try {
            var event = codec.decode(row.payload(), claim.executionId().toString());
            if (!event.eventId().equals(claim.eventId()) || !executions.matches(event)) return null;
            var execution = recovery.execution(claim.executionId(), true).orElse(null);
            if (execution == null || !"PENDING".equals(execution.status())
                    || execution.leased() || execution.failureCode() != null)
                return null;
            if (recovery.reportExists(execution.id()) || recovery.attemptExists(execution.id()))
                return null;
            var requests = recovery.requests(execution.id(), true);
            if (requests.isEmpty() || requests.stream().filter(q -> !q.shared()).count() != 1
                    || requests.stream().anyMatch(q -> !"PENDING".equals(q.status())
                        || q.cacheHit() || q.reportLinked() || q.casePk() != execution.casePk()
                        || q.detectionResultVersion() != execution.detectionResultVersion()
                        || !q.promptVersion().equals(execution.promptVersion())
                        || !q.modelVersion().equals(execution.modelVersion()))
                    || requests.stream().filter(q -> !q.shared()
                        && q.aiRequestId().equals(event.initiatingAiRequestId())
                        && q.traceId().equals(event.traceId())).count() != 1
                    || !event.caseId().equals(execution.caseId())) return null;
            return row.payload();
        } catch (AiReportExecutionCreatedCodec.InvalidEventException invalid) {
            return null;
        }
    }
}
