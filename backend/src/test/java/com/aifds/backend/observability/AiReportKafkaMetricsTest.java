package com.aifds.backend.observability;

import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.outbox.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiReportKafkaMetricsTest {
    @Test
    void separatesActualKafkaAndPollingStartsWithBoundedSourceTag() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiReportKafkaMetrics(registry, mock(OutboxRepository.class),
                mock(JdbcTemplate.class), new AiReportKafkaProperties(true,
                "execution.v1", "execution.v1.dlq", "worker-v1", 1000, 30),
                "localhost:9092");
        try {
            metrics.started("kafka");
            metrics.started("polling");
            metrics.busy();
            assertEquals(1.0, registry.get("finguardops.ai.report.starts")
                    .tag("source", "kafka").counter().count());
            assertEquals(1.0, registry.get("finguardops.ai.report.starts")
                    .tag("source", "polling").counter().count());
            assertEquals(1.0, registry.get("finguardops.kafka.records")
                    .tag("result", "busy").counter().count());
        } finally {
            metrics.close();
            registry.close();
        }
    }
}
