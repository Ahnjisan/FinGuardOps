package com.aifds.backend.persistence;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.PersistedTransactionIntake;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionIntakeWriter;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionIntakeServiceIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String TRACE_ID = "trace_provider_missing_pg_01";

    @Autowired private TransactionIntakeService intakeService;
    @Autowired private TransactionIntakeWriter intakeWriter;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private FinancialTransactionRepository transactionRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @Test
    void validationStillFinishesBeforeProviderAvailabilityAndWritesNothing() {
        TransactionCreateRequest valid = request(UUID.randomUUID());

        assertThatThrownBy(() -> intakeService.receive(
                "short",
                valid,
                TRACE_ID
        )).isInstanceOf(TransactionValidationException.class);
        assertThat(transactionRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isZero();
    }

    @Test
    void missingProviderReturnsUnavailableBeforeClaimWithZeroWrites() {
        TransactionIntakeResult result = intakeService.receive(
                key("missing-provider"),
                request(UUID.randomUUID()),
                TRACE_ID
        );

        assertThat(result)
                .isEqualTo(new TransactionIntakeResult.ProviderUnavailable());
        assertThat(transactionRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isZero();
    }

    @Test
    void missingProviderDoesNotOfferTerminalCodeOnlyReplay() {
        String key = key("missing-provider-terminal");
        TransactionCreateRequest request = request(UUID.randomUUID());
        IdempotencyClaimResult.Acquired acquired =
                (IdempotencyClaimResult.Acquired) idempotencyService.claim(
                        key,
                        command(request).toFingerprintInput()
                );
        idempotencyService.fail(
                acquired.recordId(),
                "DEPENDENCY_TIMEOUT"
        );

        assertThat(intakeService.receive(key, request, TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.ProviderUnavailable());
        IdempotencyRecord record = idempotencyRepository
                .findById(acquired.recordId())
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getFailureCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(transactionRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    @Test
    void receivedWriterCommitsTransactionLinkAndKeepsInProgress() {
        String key = key("writer-boundary");
        TransactionCreateRequest request = request(UUID.randomUUID());
        ValidatedTransactionCommand command = command(request);
        IdempotencyClaimResult.Acquired acquired =
                (IdempotencyClaimResult.Acquired) idempotencyService.claim(
                        key,
                        command.toFingerprintInput()
                );

        PersistedTransactionIntake persisted = intakeWriter.saveAndLink(
                acquired.recordId(),
                command
        );

        assertThat(persisted.processingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(transactionRepository.findByTransactionId(
                persisted.transactionId()
        )).isPresent();
        IdempotencyRecord record = idempotencyRepository
                .findById(acquired.recordId())
                .orElseThrow();
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(record.getResponseSnapshot()).isNull();
        assertThat(record.getFinishedAt()).isNull();
        assertThat(record.getFinancialTransaction()).isNotNull();
    }

    private ValidatedTransactionCommand command(
            TransactionCreateRequest request
    ) {
        return new ValidatedTransactionCommand(
                UUID.fromString(request.transactionId()),
                TransactionType.valueOf(request.transactionType()),
                new BigDecimal(request.amount()),
                request.currencyCode(),
                Instant.parse(request.occurredAt()),
                request.externalCustomerRef(),
                request.senderAccountRef(),
                request.recipientAccountRef(),
                TransactionChannel.valueOf(request.channel()),
                request.deviceRef()
        );
    }

    private TransactionCreateRequest request(UUID transactionId) {
        return new TransactionCreateRequest(
                transactionId.toString(),
                "ACCOUNT_TRANSFER",
                "1250000",
                "KRW",
                Instant.parse("2026-08-27T00:00:00Z").toString(),
                "customer_ref_provider_missing",
                "sender_ref_provider_missing",
                "recipient_ref_provider_missing",
                "MOBILE_BANKING",
                "device_ref_provider_missing"
        );
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
