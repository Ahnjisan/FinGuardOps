package com.aifds.backend.aireport.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiReportDlqMetadataTest {
    @Test
    void onlyVersionedAllowlistedHeadersCanAuthorizeAReplayCategory() {
        UUID topicId = UUID.randomUUID();
        var headers = new RecordHeaders();
        AiReportDlqMetadata.write(headers, new AiReportDlqMetadata("PRE_CLAIM_TRANSIENT",
                topicId, "source", 0, 5, "worker"));
        var record = new ConsumerRecord<String, String>("dlq", 0, 3, null, "payload");
        headers.forEach(header -> record.headers().add(header));
        var decoded = AiReportDlqMetadata.read(record);
        assertEquals("PRE_CLAIM_TRANSIENT", decoded.category());
        assertEquals(topicId, decoded.sourceTopicId());
        assertEquals(5, decoded.sourceOffset());
        record.headers().add("authorization", "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("UNKNOWN", AiReportDlqMetadata.read(record).category());
        record.headers().remove("authorization");
        assertEquals("UNKNOWN", AiReportDlqMetadata.read(
                new ConsumerRecord<>("dlq", 0, 4, null, "payload")).category());
        record.headers().remove("fgo-dlq-version");
        assertEquals("UNKNOWN", AiReportDlqMetadata.read(record).category());
    }
}
