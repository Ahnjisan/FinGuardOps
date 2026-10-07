package com.aifds.backend.aireport.dlq;

import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.aireport.exception.AiReportException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "finguardops.kafka", name = "enabled", havingValue = "true")
public class AiReportDlqReader {
    private final String bootstrap;
    private final AiReportKafkaProperties properties;

    public AiReportDlqReader(@Value("${spring.kafka.bootstrap-servers}") String bootstrap,
                             AiReportKafkaProperties properties) {
        this.bootstrap = bootstrap;
        this.properties = properties;
    }

    public UUID topicId(String topic) {
        try (AdminClient admin = admin()) {
            var id = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(3, TimeUnit.SECONDS).get(topic).topicId();
            return new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits());
        } catch (Exception unavailable) {
            throw new AiReportException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DLQ_BROKER_UNAVAILABLE");
        }
    }

    public ReadRecord read(UUID topicId, int partition, long offset) {
        if (partition < 0 || offset < 0) throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        if (!topicId(properties.dlqTopic()).equals(topicId))
            throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_TOPIC_CHANGED");
        ConsumerRecord<String, String> record = at(properties.dlqTopic(), partition, offset);
        if (!topicId(properties.dlqTopic()).equals(topicId))
            throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_TOPIC_CHANGED");
        int headerBytes = 0;
        for (var header : record.headers()) {
            headerBytes += header.key().getBytes(StandardCharsets.UTF_8).length;
            headerBytes += header.value() == null ? 0 : header.value().length;
            if (headerBytes > 1024)
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_RECORD_INVALID");
        }
        if (record.key() != null && record.key().getBytes(StandardCharsets.UTF_8).length > 128)
            throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_RECORD_INVALID");
        if (record.value() != null && record.value().getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_RECORD_INVALID");
        AiReportDlqMetadata metadata = AiReportDlqMetadata.read(record);
        boolean verified = false;
        if (metadata.sourceTopicId() != null) {
            if (!properties.topic().equals(metadata.sourceTopic())
                    || !properties.groupId().equals(metadata.sourceGroup())
                    || !topicId(metadata.sourceTopic()).equals(metadata.sourceTopicId()))
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_SOURCE_CHANGED");
            var original = at(metadata.sourceTopic(), metadata.sourcePartition(), metadata.sourceOffset());
            if (!topicId(metadata.sourceTopic()).equals(metadata.sourceTopicId()))
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_SOURCE_CHANGED");
            if (!java.util.Objects.equals(original.key(), record.key())
                    || !java.util.Objects.equals(original.value(), record.value()))
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_SOURCE_MISMATCH");
            verified = true;
        }
        return new ReadRecord(record, metadata, verified);
    }

    public boolean sourceRecovered(AiReportDlqMetadata metadata) {
        if (metadata.sourceTopicId() == null) return false;
        if (!topicId(metadata.sourceTopic()).equals(metadata.sourceTopicId()))
            throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_SOURCE_CHANGED");
        try (AdminClient admin = admin()) {
            var offsets = admin.listConsumerGroupOffsets(properties.groupId())
                    .partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
            var committed = offsets.get(new TopicPartition(metadata.sourceTopic(), metadata.sourcePartition()));
            boolean recovered = committed != null && committed.offset() > metadata.sourceOffset();
            if (!topicId(metadata.sourceTopic()).equals(metadata.sourceTopicId()))
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_SOURCE_CHANGED");
            return recovered;
        } catch (AiReportException expected) {
            throw expected;
        } catch (Exception unavailable) {
            throw new AiReportException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DLQ_BROKER_UNAVAILABLE");
        }
    }

    private ConsumerRecord<String, String> at(String topic, int partition, long offset) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1,
                ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 8192,
                ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 8192,
                ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000))) {
            TopicPartition coordinate = new TopicPartition(topic, partition);
            consumer.assign(List.of(coordinate));
            long beginning = consumer.beginningOffsets(List.of(coordinate)).get(coordinate);
            long end = consumer.endOffsets(List.of(coordinate)).get(coordinate);
            if (offset < beginning || offset >= end)
                throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_OFFSET_UNAVAILABLE");
            consumer.seek(coordinate, offset);
            for (int i = 0; i < 3; i++) {
                for (var record : consumer.poll(Duration.ofSeconds(2))) {
                    if (record.partition() == partition && record.offset() == offset) return record;
                    if (record.offset() > offset) break;
                }
            }
            throw new AiReportException(HttpStatus.CONFLICT, "AI_DLQ_OFFSET_UNAVAILABLE");
        } catch (AiReportException expected) {
            throw expected;
        } catch (Exception unavailable) {
            throw new AiReportException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DLQ_BROKER_UNAVAILABLE");
        }
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of("bootstrap.servers", bootstrap,
                "request.timeout.ms", "3000", "default.api.timeout.ms", "3000"));
    }

    public record ReadRecord(ConsumerRecord<String, String> record,
                             AiReportDlqMetadata metadata, boolean sourceVerified) { }
}
