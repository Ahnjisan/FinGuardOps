package com.aifds.backend.aireport.event;

import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.service.AiReportWorker;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import com.aifds.backend.observability.LocalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

@Component
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportExecutionConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiReportExecutionConsumer.class);
    private final AiReportExecutionCreatedCodec codec;
    private final AiReportExecutionRepository executions;
    private final AiReportWorker worker;
    private final AiReportKafkaMetrics metrics;

    public AiReportExecutionConsumer(AiReportExecutionCreatedCodec codec,
                                     AiReportExecutionRepository executions,
                                     AiReportWorker worker, AiReportKafkaMetrics metrics) {
        this.codec = codec;
        this.executions = executions;
        this.worker = worker;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${finguardops.kafka.topic}",
            groupId = "${finguardops.kafka.group-id}", concurrency = "1",
            autoStartup = "${finguardops.kafka.consumer-enabled:true}")
    public void consume(String payload, @Header(KafkaHeaders.RECEIVED_KEY) String key,
                        Acknowledgment ack) {
        AiReportExecutionCreated event = codec.decode(payload, key);
        boolean matches;
        try {
            matches = executions.matches(event);
        } catch (TransientDataAccessException | DataAccessResourceFailureException beforeClaim) {
            throw new PreClaimTransientException();
        }
        if (!matches) {
            throw new AiReportExecutionCreatedCodec.InvalidEventException("event DB relationship");
        }
        LOGGER.info("event=ai_report_kafka_received executionId={} eventId={} otelTraceId={}",
                event.executionId(), event.eventId(), LocalTrace.currentTraceId());
        AiReportWorker.StartResult result = worker.runExecution(event.executionId());
        if (result == AiReportWorker.StartResult.UNAVAILABLE) {
            metrics.failed();
            throw new PreClaimTransientException();
        }
        if (result == AiReportWorker.StartResult.NOT_CLAIMED) {
            AiReportStatus status = executions.status(event.executionId()).orElseThrow(
                    () -> new AiReportExecutionCreatedCodec.InvalidEventException("execution absent"));
            if (status == AiReportStatus.PENDING) metrics.busy();
            else metrics.duplicate();
        } else {
            metrics.processed();
        }
        ack.acknowledge();
        LOGGER.info("event=ai_report_kafka_acked executionId={} result={} otelTraceId={}",
                event.executionId(), result, LocalTrace.currentTraceId());
    }
}
