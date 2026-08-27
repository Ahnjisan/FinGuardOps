package com.aifds.backend.transaction.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.service.ExternalRiskFailureSnapshotService;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.transaction.validation.TransactionRequestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionIntakeServiceTest {

    private static final String KEY = "intake-unit-key";
    private static final String TRACE_ID = "trace_intake_unit_01";
    private static final long RECORD_ID = 42L;

    @Mock private IdempotencyKeyValidator idempotencyKeyValidator;
    @Mock private TransactionRequestValidator transactionRequestValidator;
    @Mock private TransactionRequestFingerprint transactionRequestFingerprint;
    @Mock private IdempotencyService idempotencyService;
    @Mock private TransactionSynchronousProcessingCoordinator coordinator;
    @Mock private TransactionIntakeSnapshotCodec snapshotCodec;
    @Mock private ExternalRiskFailureSnapshotService failureSnapshotService;

    private TransactionIntakeService service;

    @BeforeEach
    void setUp() {
        service = new TransactionIntakeService(
                idempotencyKeyValidator,
                transactionRequestValidator,
                transactionRequestFingerprint,
                idempotencyService,
                coordinator,
                snapshotCodec,
                failureSnapshotService
        );
    }

    @Test
    void validatesAndFingerprintsBeforeUnavailableProviderStopsClaim() {
        TransactionCreateRequest request = request();
        ValidatedTransactionCommand command = command();
        when(idempotencyKeyValidator.validate(KEY)).thenReturn(KEY);
        when(transactionRequestValidator.validate(request)).thenReturn(command);
        when(transactionRequestFingerprint.calculate(
                command.toFingerprintInput()
        )).thenReturn("fingerprint");
        when(coordinator.isAvailable()).thenReturn(false);

        assertThat(service.receive(KEY, request, TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.ProviderUnavailable());

        InOrder order = inOrder(
                idempotencyKeyValidator,
                transactionRequestValidator,
                transactionRequestFingerprint,
                coordinator
        );
        order.verify(idempotencyKeyValidator).validate(KEY);
        order.verify(transactionRequestValidator).validate(request);
        order.verify(transactionRequestFingerprint).calculate(
                command.toFingerprintInput()
        );
        order.verify(coordinator).isAvailable();
        verifyNoInteractions(idempotencyService, snapshotCodec,
                failureSnapshotService);
    }

    @Test
    void acquiredClaimDelegatesOnceWithCurrentTrace() {
        TransactionCreateRequest request = request();
        ValidatedTransactionCommand command = stubAvailable(request);
        TransactionIntakeResult.Received received =
                new TransactionIntakeResult.Received(finalSnapshot(), 201);
        when(idempotencyService.claim(KEY, command.toFingerprintInput()))
                .thenReturn(new IdempotencyClaimResult.Acquired(RECORD_ID));
        when(coordinator.process(RECORD_ID, command, TRACE_ID))
                .thenReturn(received);

        assertThat(service.receive(KEY, request, TRACE_ID)).isSameAs(received);
        verify(coordinator).process(RECORD_ID, command, TRACE_ID);
    }

    @Test
    void mapsConflictInProgressAndCodeOnlyFailureWithoutProcessing() {
        assertThat(receiveWithClaim(new IdempotencyClaimResult.KeyConflict()))
                .isInstanceOf(TransactionIntakeResult.KeyConflict.class);
        assertThat(receiveWithClaim(new IdempotencyClaimResult.InProgress()))
                .isInstanceOf(TransactionIntakeResult.InProgress.class);
        assertThat(receiveWithClaim(new IdempotencyClaimResult.Failed(
                "DEPENDENCY_UNAVAILABLE"
        ))).isEqualTo(new TransactionIntakeResult.PreviousFailure(
                "DEPENDENCY_UNAVAILABLE"
        ));
        verify(coordinator, never()).process(anyLong(), any(), anyString());
    }

    @Test
    void replaysCompletedSnapshotThroughExistingDispatcher() {
        String stored = "{\"stored\":true}";
        TransactionIntakeSnapshotReplay decoded =
                new TransactionIntakeSnapshotReplay(finalSnapshot(), 201);
        when(snapshotCodec.decode(stored)).thenReturn(decoded);

        assertThat(receiveWithClaim(new IdempotencyClaimResult.Completed(stored)))
                .isEqualTo(new TransactionIntakeResult.CompletedReplay(
                        decoded.snapshot(),
                        decoded.httpStatus()
                ));
    }

    @Test
    void strictDecodesTypedFailureAndDistinguishesReplay() {
        Instant finishedAt = Instant.parse("2026-08-27T01:00:00Z");
        IdempotencyClaimResult.FailedWithSnapshot stored =
                new IdempotencyClaimResult.FailedWithSnapshot(
                        "DEPENDENCY_TIMEOUT",
                        "{\"snapshotType\":\"external-risk-failure\"}",
                        finishedAt
                );
        ExternalRiskFailureSnapshot decoded = ExternalRiskFailureSnapshot.from(
                ExternalRiskFailureCategory.TIMEOUT,
                finishedAt
        );
        when(failureSnapshotService.decode(stored)).thenReturn(decoded);

        assertThat(receiveWithClaim(stored)).isEqualTo(
                new TransactionIntakeResult.ExternalRiskFailureReplay(
                        503,
                        "DEPENDENCY_TIMEOUT",
                        "탐지 서비스를 사용할 수 없습니다."
                )
        );
    }

    @Test
    void validationFailureStopsBeforeFingerprintAndAvailability() {
        RuntimeException original = new IllegalArgumentException("invalid");
        when(idempotencyKeyValidator.validate(KEY)).thenThrow(original);

        assertThatThrownBy(() -> service.receive(KEY, request(), TRACE_ID))
                .isSameAs(original);
        verifyNoInteractions(
                transactionRequestValidator,
                transactionRequestFingerprint,
                coordinator,
                idempotencyService
        );
    }

    @Test
    void typedFailureResultRejectsUnapprovedProviderDetail() {
        assertThatThrownBy(() ->
                new TransactionIntakeResult.ExternalRiskFailure(
                        503,
                        "PROVIDER_SECRET",
                        "credential=secret"
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("credential=secret");
    }

    private TransactionIntakeResult receiveWithClaim(
            IdempotencyClaimResult claim
    ) {
        TransactionCreateRequest request = request();
        ValidatedTransactionCommand command = stubAvailable(request);
        when(idempotencyService.claim(KEY, command.toFingerprintInput()))
                .thenReturn(claim);
        return service.receive(KEY, request, TRACE_ID);
    }

    private ValidatedTransactionCommand stubAvailable(
            TransactionCreateRequest request
    ) {
        ValidatedTransactionCommand command = command();
        when(idempotencyKeyValidator.validate(KEY)).thenReturn(KEY);
        when(transactionRequestValidator.validate(request)).thenReturn(command);
        when(coordinator.isAvailable()).thenReturn(true);
        return command;
    }

    private TransactionIntakeSnapshot finalSnapshot() {
        return new TransactionIntakeSnapshot(
                command().transactionId(),
                TransactionProcessingStatus.APPROVED,
                "LOW",
                "APPROVED",
                "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
                null,
                Instant.parse("2026-07-23T01:15:31Z")
        );
    }

    private TransactionCreateRequest request() {
        ValidatedTransactionCommand command = command();
        return new TransactionCreateRequest(
                command.transactionId().toString(),
                command.transactionType().name(),
                command.amount().toPlainString(),
                command.currencyCode(),
                command.occurredAt().toString(),
                command.externalCustomerRef(),
                command.senderAccountRef(),
                command.recipientAccountRef(),
                command.channel().name(),
                command.deviceRef()
        );
    }

    private ValidatedTransactionCommand command() {
        return new ValidatedTransactionCommand(
                UUID.fromString("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-23T01:10:00Z"),
                "cust_ref_intake_unit",
                "acct_ref_intake_unit_sender",
                "acct_ref_intake_unit_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_intake_unit"
        );
    }
}
