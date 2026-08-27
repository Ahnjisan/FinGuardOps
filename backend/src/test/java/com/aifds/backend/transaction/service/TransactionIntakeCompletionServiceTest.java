package com.aifds.backend.transaction.service;

import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionIntakeCompletionServiceTest {

    private static final long RECORD_ID = 17L;
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final UUID RESULT_ID = UUID.fromString(
            "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101"
    );

    @Mock private TransactionIntakeSnapshotCodec snapshotCodec;
    @Mock private IdempotencyService idempotencyService;
    @Mock private DatabaseTransactionTimestampProvider timestampProvider;

    @Test
    void createsV2SnapshotAndCompletesWithOneDatabaseTimestamp() {
        Instant createdAt = Instant.parse("2026-07-23T01:15:31Z");
        Instant finalizedAt = Instant.parse("2026-07-23T01:15:33.654321Z");
        RiskResponseFinalizationResult finalization = finalization();
        TransactionFinalResponseSnapshot expected =
                new TransactionFinalResponseSnapshot(finalization, createdAt);
        JsonNode encoded = new ObjectMapper().createObjectNode()
                .put("codecVersion", "transaction-intake-snapshot-envelope-v2");
        when(timestampProvider.currentTransactionTimestamp())
                .thenReturn(finalizedAt);
        when(snapshotCodec.encodeV2(expected, 201, finalizedAt))
                .thenReturn(encoded);
        when(idempotencyService.complete(
                RECORD_ID,
                TRANSACTION_ID,
                encoded,
                finalizedAt
        )).thenReturn(new IdempotencyClaimResult.Completed(encoded.toString()));

        TransactionIntakeResult.Received result = service().complete(
                RECORD_ID,
                finalization,
                createdAt
        );

        assertThat(result.snapshot())
                .isEqualTo(expected.toTransactionIntakeSnapshot());
        assertThat(result.httpStatus()).isEqualTo(201);
        InOrder order = inOrder(timestampProvider, snapshotCodec,
                idempotencyService);
        order.verify(timestampProvider).currentTransactionTimestamp();
        order.verify(snapshotCodec).encodeV2(expected, 201, finalizedAt);
        order.verify(idempotencyService).complete(
                RECORD_ID,
                TRANSACTION_ID,
                encoded,
                finalizedAt
        );
    }

    @Test
    void completionBoundaryIsRequiresNew() throws Exception {
        Method complete = TransactionIntakeCompletionService.class.getMethod(
                "complete",
                long.class,
                RiskResponseFinalizationResult.class,
                Instant.class
        );
        Transactional transactional = complete.getAnnotation(
                Transactional.class
        );
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    private TransactionIntakeCompletionService service() {
        return new TransactionIntakeCompletionService(
                snapshotCodec,
                idempotencyService,
                timestampProvider
        );
    }

    private RiskResponseFinalizationResult finalization() {
        return new RiskResponseFinalizationResult(
                TRANSACTION_ID,
                RESULT_ID,
                RiskLevel.LOW,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                false
        );
    }
}
