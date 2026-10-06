package com.aifds.backend.aireport.config;

import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import org.apache.kafka.clients.admin.NewTopic;
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
                                                  AiReportKafkaMetrics metrics) {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            metrics.failed();
            try {
                template.send(properties.dlqTopic(), String.valueOf(record.key()),
                        String.valueOf(record.value())).get(10, TimeUnit.SECONDS);
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
}
