package com.aifds.backend.aireport.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A deliberately small, versioned set of headers. Never copy arbitrary source headers. */
public record AiReportDlqMetadata(String category, UUID sourceTopicId, String sourceTopic,
                                  int sourcePartition, long sourceOffset, String sourceGroup) {
    private static final String PREFIX = "fgo-dlq-";
    private static final Set<String> CATEGORIES = Set.of("INVALID_EVENT", "PRE_CLAIM_TRANSIENT", "UNKNOWN");
    private static final Set<String> KEYS = Set.of("version", "category", "source-topic-id",
            "source-topic", "source-partition", "source-offset", "source-group");

    public static void write(Headers headers, AiReportDlqMetadata value) {
        put(headers, "version", "1");
        put(headers, "category", value.category);
        put(headers, "source-topic-id", value.sourceTopicId == null ? "" : value.sourceTopicId.toString());
        put(headers, "source-topic", value.sourceTopic);
        put(headers, "source-partition", Integer.toString(value.sourcePartition));
        put(headers, "source-offset", Long.toString(value.sourceOffset));
        put(headers, "source-group", value.sourceGroup);
    }

    public static AiReportDlqMetadata read(ConsumerRecord<String, String> record) {
        try {
            var seen = new HashSet<String>();
            for (Header header : record.headers()) {
                if (!header.key().startsWith(PREFIX)
                        || !KEYS.contains(header.key().substring(PREFIX.length()))
                        || !seen.add(header.key())) return unknown();
            }
            if (seen.size() != KEYS.size()) return unknown();
            if (!"1".equals(get(record.headers(), "version"))) return unknown();
            String category = get(record.headers(), "category");
            if (!CATEGORIES.contains(category)) return unknown();
            String id = get(record.headers(), "source-topic-id");
            UUID topicId = id.isBlank() ? null : UUID.fromString(id);
            String topic = get(record.headers(), "source-topic");
            String group = get(record.headers(), "source-group");
            int partition = Integer.parseInt(get(record.headers(), "source-partition"));
            long offset = Long.parseLong(get(record.headers(), "source-offset"));
            if (topic.isBlank() || group.isBlank() || partition < 0 || offset < 0) return unknown();
            return new AiReportDlqMetadata(category, topicId, topic, partition, offset, group);
        } catch (RuntimeException invalid) {
            return unknown();
        }
    }

    private static AiReportDlqMetadata unknown() {
        return new AiReportDlqMetadata("UNKNOWN", null, "", -1, -1, "");
    }

    private static void put(Headers headers, String key, String value) {
        headers.add(PREFIX + key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String get(Headers headers, String key) {
        Header found = headers.lastHeader(PREFIX + key);
        if (found == null || found.value() == null || found.value().length > 128)
            throw new IllegalArgumentException("missing DLQ metadata");
        return new String(found.value(), StandardCharsets.UTF_8);
    }
}
