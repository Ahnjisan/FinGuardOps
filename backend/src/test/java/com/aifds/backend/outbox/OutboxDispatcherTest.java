package com.aifds.backend.outbox;

import com.aifds.backend.aireport.config.AiReportKafkaProperties;
import com.aifds.backend.observability.AiReportKafkaMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final AiReportKafkaMetrics metrics = mock(AiReportKafkaMetrics.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AiReportKafkaProperties properties = new AiReportKafkaProperties(true,
            "topic", "topic.dlq", "group", 1000, 30);
    private final OutboxRepository.Claim claim = new OutboxRepository.Claim(1,
            UUID.randomUUID(), UUID.randomUUID(), "{}", UUID.randomUUID(), 1);

    private OutboxDispatcher dispatcher() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(outbox.claim(30)).thenReturn(Optional.of(claim));
        return new OutboxDispatcher(outbox, kafka, properties, metrics, manager);
    }

    @Test
    void marksPublishedOnlyAfterBrokerAck() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        ack.complete(mock(SendResult.class));
        when(kafka.send("topic", claim.executionId().toString(), claim.payload())).thenReturn(ack);
        when(outbox.published(claim)).thenReturn(true);
        dispatcher().dispatch();
        var order = inOrder(kafka, outbox);
        order.verify(kafka).send("topic", claim.executionId().toString(), claim.payload());
        order.verify(outbox).published(claim);
    }

    @Test
    void failedAckLeavesRowRetryable() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send("topic", claim.executionId().toString(), claim.payload())).thenReturn(failed);
        dispatcher().dispatch();
        verify(outbox).failed(claim, "PUBLISH_FAILED");
        verify(outbox, never()).published(claim);
    }

    @Test
    void brokerAckThenDbMarkFailureCanPublishSameEventAgain() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        ack.complete(mock(SendResult.class));
        when(kafka.send("topic", claim.executionId().toString(), claim.payload())).thenReturn(ack);
        when(outbox.published(claim)).thenThrow(new IllegalStateException("database unavailable"));
        dispatcher().dispatch();
        verify(outbox).failed(claim, "PUBLISH_FAILED");
        verify(metrics).publishFailed();
    }
}
