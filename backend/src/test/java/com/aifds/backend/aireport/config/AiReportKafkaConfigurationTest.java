package com.aifds.backend.aireport.config;

import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiReportKafkaConfigurationTest {
    @Test
    void defaultContextHasNoAiReportKafkaTopics() {
        new ApplicationContextRunner().withUserConfiguration(AiReportKafkaConfiguration.class)
                .run(context -> {
                    assertFalse(context.containsBean("aiReportExecutionTopic"));
                    assertFalse(context.containsBean("aiReportExecutionDlq"));
                });
    }

    @Test
    void createsOnlyOnePartitionForEachNamedTopic() {
        var properties = new AiReportKafkaProperties(true, "execution.v1", "execution.v1.dlq",
                "worker-v1", 1000, 30);
        var config = new AiReportKafkaConfiguration();
        assertEquals("execution.v1", config.aiReportExecutionTopic(properties).name());
        assertEquals(1, config.aiReportExecutionTopic(properties).numPartitions());
        assertEquals((short) 1, config.aiReportExecutionTopic(properties).replicationFactor());
        assertEquals("execution.v1.dlq", config.aiReportExecutionDlq(properties).name());
        assertThrows(IllegalArgumentException.class, () -> new AiReportKafkaProperties(true,
                "", "dlq", "group", 1000, 30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void poisonGoesToDlqOnlyAfterBrokerAckAndFailedDlqAckStaysUnrecovered() {
        var properties = new AiReportKafkaProperties(true, "execution.v1", "execution.v1.dlq",
                "worker-v1", 1000, 30);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AiReportKafkaMetrics metrics = mock(AiReportKafkaMetrics.class);
        var handler = new AiReportKafkaConfiguration().aiReportKafkaErrorHandler(kafka,
                properties, metrics);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("execution.v1", 0,
                3L, "execution-key", "invalid-json");
        var consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        var failed = new CompletableFuture<SendResult<String, String>>();
        failed.completeExceptionally(new IllegalStateException("DLQ broker unavailable"));
        when(kafka.send("execution.v1.dlq", "execution-key", "invalid-json"))
                .thenReturn(failed);
        var poison = new AiReportExecutionCreatedCodec.InvalidEventException("event contract");
        assertFalse(handler.handleOne(poison, record, consumer, container));
        verify(metrics, never()).dlq();
        verify(metrics).failed();

        var acknowledged = new CompletableFuture<SendResult<String, String>>();
        acknowledged.complete(mock(SendResult.class));
        when(kafka.send("execution.v1.dlq", "execution-key", "invalid-json"))
                .thenReturn(acknowledged);
        assertTrue(handler.handleOne(poison, record, consumer, container));
        verify(metrics).dlq();
        verify(metrics, times(2)).failed();
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientFailureHasBoundedRetriesBeforeDlq() {
        var properties = new AiReportKafkaProperties(true, "execution.v1", "execution.v1.dlq",
                "worker-v1", 1000, 30);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AiReportKafkaMetrics metrics = mock(AiReportKafkaMetrics.class);
        var handler = new AiReportKafkaConfiguration().aiReportKafkaErrorHandler(kafka,
                properties, metrics);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("execution.v1", 0,
                4L, "execution-key", "valid-shape");
        var consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        var acknowledged = new CompletableFuture<SendResult<String, String>>();
        acknowledged.complete(mock(SendResult.class));
        when(kafka.send("execution.v1.dlq", "execution-key", "valid-shape"))
                .thenReturn(acknowledged);
        var outage = new IllegalStateException("temporary database outage");
        assertFalse(handler.handleOne(outage, record, consumer, container));
        assertFalse(handler.handleOne(outage, record, consumer, container));
        assertTrue(handler.handleOne(outage, record, consumer, container));
        verify(kafka, times(1)).send("execution.v1.dlq", "execution-key", "valid-shape");
        verify(metrics).dlq();
    }
}
