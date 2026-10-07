package com.aifds.backend.aireport.config;

import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.aireport.event.PreClaimTransientException;
import com.aifds.backend.aireport.dlq.AiReportDlqMetadata;
import com.aifds.backend.aireport.dlq.AiReportDlqReader;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.util.backoff.FixedBackOff;

import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(AiReportKafkaProperties.class)
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportKafkaConfiguration {
    @Bean
    NewTopic aiReportExecutionTopic(AiReportKafkaProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic aiReportExecutionDlq(AiReportKafkaProperties properties) {
        return TopicBuilder.name(properties.dlqTopic()).partitions(1).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler aiReportKafkaErrorHandler(KafkaTemplate<String, String> template,
                                                  AiReportKafkaProperties properties,
                                                  AiReportKafkaMetrics metrics,
                                                  AiReportDlqReader reader) {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            metrics.failed();
            try {
                String category = category(exception);
                java.util.UUID sourceTopicId = null;
                try {
                    sourceTopicId = reader.topicId(record.topic());
                } catch (RuntimeException unavailable) {
                    category = "UNKNOWN";
                }
                var headers = new RecordHeaders();
                AiReportDlqMetadata.write(headers, new AiReportDlqMetadata(category,
                        sourceTopicId, record.topic(), record.partition(), record.offset(),
                        properties.groupId()));
                template.send(new ProducerRecord<String, String>(properties.dlqTopic(), null,
                        (String) record.key(), (String) record.value(), headers)).get(10, TimeUnit.SECONDS);
                metrics.dlq();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DLQ acknowledgment interrupted", interrupted);
            } catch (Exception failed) {
                throw new IllegalStateException("DLQ acknowledgment failed", failed);
            }
        }, new FixedBackOff(1000, 2));
        handler.addNotRetryableExceptions(AiReportExecutionCreatedCodec.InvalidEventException.class);
        handler.setRetryListeners((record, exception, attempt) -> metrics.reprocessed());
        handler.setCommitRecovered(true);
        return handler;
    }

    private String category(Throwable failure) {
        for (Throwable next = failure; next != null; next = next.getCause()) {
            if (next instanceof AiReportExecutionCreatedCodec.InvalidEventException) return "INVALID_EVENT";
            if (next instanceof PreClaimTransientException) return "PRE_CLAIM_TRANSIENT";
        }
        return "UNKNOWN";
    }
}
