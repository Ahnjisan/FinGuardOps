package com.aifds.backend.transaction.service;

import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionIntakeCompletionServiceTest {

    private static final long RECORD_ID = 17L;

    @Mock
    private TransactionIntakeWriter transactionIntakeWriter;
    @Mock
    private TransactionIntakeSnapshotCodec snapshotCodec;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private Clock clock;

    @Test
    void persistsBuildsSnapshotCompletesAndReturnsTheSameTypedResult() {
        ValidatedTransactionCommand command = command();
        PersistedTransactionIntake persisted =
                new PersistedTransactionIntake(
                        command.transactionId(),
                        TransactionProcessingStatus.RECEIVED,
                        Instant.parse("2026-07-23T01:15:31Z")
                );
        TransactionIntakeSnapshot expected =
                TransactionIntakeSnapshot.received(persisted);
        JsonNode encoded = new ObjectMapper().createObjectNode()
                .put("transactionId", command.transactionId().toString());
        Instant clockInstant =
                Instant.parse("2026-07-23T01:15:33.654321987Z");
        Instant finalizedAt =
                Instant.parse("2026-07-23T01:15:33.654321Z");

        when(transactionIntakeWriter.saveAndLink(RECORD_ID, command))
                .thenReturn(persisted);
        when(clock.instant()).thenReturn(clockInstant);
        when(snapshotCodec.encode(expected, 201, finalizedAt))
                .thenReturn(encoded);
        when(idempotencyService.complete(
                RECORD_ID,
                command.transactionId(),
                encoded,
                finalizedAt
        )).thenReturn(new IdempotencyClaimResult.Completed(
                encoded.toString()
        ));

        TransactionIntakeResult.Received result =
                new TransactionIntakeCompletionService(
                        transactionIntakeWriter,
                        snapshotCodec,
                        idempotencyService,
                        clock
                ).complete(RECORD_ID, command);

        assertThat(result.snapshot()).isEqualTo(expected);
        assertThat(result.httpStatus()).isEqualTo(201);
        InOrder order = inOrder(
                transactionIntakeWriter,
                clock,
                snapshotCodec,
                idempotencyService
        );
        order.verify(transactionIntakeWriter)
                .saveAndLink(RECORD_ID, command);
        order.verify(clock).instant();
        order.verify(snapshotCodec).encode(expected, 201, finalizedAt);
        order.verify(idempotencyService).complete(
                RECORD_ID,
                command.transactionId(),
                encoded,
                finalizedAt
        );
        verify(clock).instant();
        verifyNoMoreInteractions(clock);
    }

    private ValidatedTransactionCommand command() {
        return new ValidatedTransactionCommand(
                UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-23T01:10:00Z"),
                "cust_ref_completion_unit",
                "acct_ref_completion_sender",
                "acct_ref_completion_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_completion_unit"
        );
    }
}
