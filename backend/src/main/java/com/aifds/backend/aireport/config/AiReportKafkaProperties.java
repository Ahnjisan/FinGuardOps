package com.aifds.backend.aireport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finguardops.kafka")
public record AiReportKafkaProperties(boolean enabled, String topic, String dlqTopic,
                                      String groupId, long outboxPollMs,
                                      int outboxLeaseSeconds) {
    public AiReportKafkaProperties {
        if (enabled && (topic == null || topic.isBlank() || dlqTopic == null
                || dlqTopic.isBlank() || groupId == null || groupId.isBlank()
                || outboxPollMs < 100 || outboxLeaseSeconds < 20)) {
            throw new IllegalArgumentException("Invalid AI report Kafka configuration");
        }
    }
}
