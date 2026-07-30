package com.aifds.backend.persistence;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.fingerprint.TransactionFingerprintInput;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import com.aifds.backend.transaction.service.TransactionIntakeWriter;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionIntakeServiceIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String OPERATION_SCOPE =
            "POST:/api/v1/transactions";

    @Autowired
    private TransactionIntakeService transactionIntakeService;

    @Autowired
    private TransactionIntakeWriter transactionIntakeWriter;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private FinancialTransactionRepository financialTransactionRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void validationFailuresDoNotClaimOrPersistRows() {
        TransactionCreateRequest valid = request(UUID.randomUUID());

        assertThatThrownBy(() -> transactionIntakeService.receive(
                "short",
                valid
        )).isInstanceOf(TransactionValidationException.class);
        assertNoIntakeRows();

        TransactionCreateRequest formatInvalid = copy(
                valid,
                valid.transactionId(),
                "1.5",
                valid.recipientAccountRef(),
                valid.channel()
        );
        assertThatThrownBy(() -> transactionIntakeService.receive(
                key("format"),
                formatInvalid
        )).isInstanceOf(TransactionValidationException.class);
        assertNoIntakeRows();

        TransactionCreateRequest domainInvalid = copy(
                valid,
                valid.transactionId(),
                valid.amount(),
                null,
                valid.channel()
        );
        assertThatThrownBy(() -> transactionIntakeService.receive(
                key("domain"),
                domainInvalid
        )).isInstanceOf(TransactionValidationException.class);
        assertNoIntakeRows();
    }

    @Test
    void storesReceivedTransactionAndCompletesTypedSnapshot() {
        String key = key("received");
        TransactionCreateRequest request = request(UUID.randomUUID());

        TransactionIntakeResult result =
                transactionIntakeService.receive(key, request);

        assertThat(result).isInstanceOf(TransactionIntakeResult.Received.class);
        TransactionIntakeResult.Received received =
                (TransactionIntakeResult.Received) result;
        assertThat(received.snapshot().transactionId())
                .isEqualTo(UUID.fromString(request.transactionId()));
        assertThat(received.snapshot().processingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);

        FinancialTransaction stored = financialTransactionRepository
                .findByTransactionId(received.snapshot().transactionId())
                .orElseThrow();
        assertThat(stored.getTransactionType())
                .isEqualTo(TransactionType.ACCOUNT_TRANSFER);
        assertThat(stored.getAmount()).isEqualByComparingTo("1250000.0000");
        assertThat(stored.getCurrencyCode()).isEqualTo("KRW");
        assertThat(stored.getOccurredAt())
                .isEqualTo(Instant.parse(request.occurredAt()));
        assertThat(stored.getExternalCustomerRef())
                .isEqualTo(request.externalCustomerRef());
        assertThat(stored.getSenderAccountRef())
                .isEqualTo(request.senderAccountRef());
        assertThat(stored.getRecipientAccountRef())
                .isEqualTo(request.recipientAccountRef());
        assertThat(stored.getChannel())
                .isEqualTo(TransactionChannel.MOBILE_BANKING);
        assertThat(stored.getDeviceRef()).isEqualTo(request.deviceRef());
        assertThat(stored.getProcessingStatus())
                .isEqualTo(TransactionProcessingStatus.RECEIVED);
        assertThat(received.snapshot().createdAt())
                .isEqualTo(stored.getCreatedAt());

        IdempotencyRecord record = recordByKey(key);
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        JsonNode envelope = record.getResponseSnapshot();
        assertThat(envelope.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "responseBody",
                        "httpStatus",
                        "responseSchemaVersion",
                        "codecVersion",
                        "finalizedAt"
                );
        assertThat(envelope.get("httpStatus").intValue()).isEqualTo(201);
        assertThat(envelope.get("responseSchemaVersion").textValue())
                .isEqualTo("transaction-create-response-v1");
        assertThat(envelope.get("codecVersion").textValue())
                .isEqualTo("transaction-intake-snapshot-envelope-v1");
        assertThat(Instant.parse(envelope.get("finalizedAt").textValue()))
                .isEqualTo(record.getFinishedAt());
        JsonNode responseBody = envelope.get("responseBody");
        assertThat(responseBody.get("riskLevel").isNull())
                .isTrue();
        assertThat(responseBody.get("riskResponseOutcome").isNull())
                .isTrue();
        assertThat(responseBody.get("adoptedDetectionResultId").isNull())
                .isTrue();
        assertThat(responseBody.get("caseId").isNull())
                .isTrue();
        assertThat(envelope.has("traceId")).isFalse();
        assertThat(responseBody.has("traceId")).isFalse();
        assertThat(envelope.has("idempotencyRecordId"))
                .isFalse();
        assertThat(envelope.has("fingerprint")).isFalse();
        assertThat(record.getFailureCode()).isNull();
        assertThat(record.getFinishedAt()).isNotNull();
        assertThat(linkedTransactionId(record.getId())).isEqualTo(stored.getId());
    }

    @Test
    void preventsSameKeyReplayAndKeyConflictFromCreatingAnotherTransaction() {
        String key = key("same-key");
        TransactionCreateRequest request = request(UUID.randomUUID());
        TransactionIntakeResult first =
                transactionIntakeService.receive(key, request);

        TransactionIntakeResult sameRequest =
                transactionIntakeService.receive(key, request);
        TransactionCreateRequest differentRequest = copy(
                request,
                request.transactionId(),
                "1250001",
                request.recipientAccountRef(),
                request.channel()
        );
        TransactionIntakeResult conflict =
                transactionIntakeService.receive(key, differentRequest);

        assertThat(first).isInstanceOf(TransactionIntakeResult.Received.class);
        assertThat(sameRequest)
                .isInstanceOf(TransactionIntakeResult.CompletedReplay.class);
        assertThat(((TransactionIntakeResult.CompletedReplay) sameRequest)
                .snapshot()).isEqualTo(
                ((TransactionIntakeResult.Received) first).snapshot()
        );
        assertThat(conflict)
                .isInstanceOf(TransactionIntakeResult.KeyConflict.class);
        assertThat(countRows("financial_transaction")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
    }

    @Test
    void returnsCompletedSnapshotAndPreviousFailureWithoutRetrying() {
        String completedKey = key("completed");
        TransactionCreateRequest completedRequest = request(UUID.randomUUID());
        TransactionIntakeResult.Received completedReceived =
                (TransactionIntakeResult.Received) transactionIntakeService.receive(
                        completedKey,
                        completedRequest
                );

        TransactionIntakeResult completedReplay =
                transactionIntakeService.receive(
                        completedKey,
                        completedRequest
                );

        assertThat(completedReplay)
                .isEqualTo(new TransactionIntakeResult.CompletedReplay(
                        completedReceived.snapshot(),
                        201
                ));

        String failedKey = key("failed");
        TransactionCreateRequest failedRequest = request(UUID.randomUUID());
        long failedRecordId = acquiredRecordId(idempotencyService.claim(
                        failedKey,
                        fingerprintInput(failedRequest)
                ));
        idempotencyService.fail(failedRecordId, "DEPENDENCY_TIMEOUT");

        TransactionIntakeResult previousFailure =
                transactionIntakeService.receive(failedKey, failedRequest);

        assertThat(previousFailure)
                .isEqualTo(new TransactionIntakeResult.PreviousFailure(
                        "DEPENDENCY_TIMEOUT"
                ));
        assertThat(countRows("financial_transaction")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(2);
    }

    @Test
    void rollsBackTransactionWhenCompletedTransitionFailsThenFailsClaim() {
        installCompletionFailureTrigger();
        String key = key("completion-failure");
        TransactionCreateRequest request = request(UUID.randomUUID());

        try {
            assertThatThrownBy(() -> transactionIntakeService.receive(key, request))
                    .isInstanceOf(JpaSystemException.class)
                    .satisfies(exception -> {
                        SQLException sqlException = findSqlException(exception);
                        assertThatObject(sqlException).isNotNull();
                        assertThat(sqlException.getSQLState()).isEqualTo("P0001");
                    });
        } finally {
            removeCompletionFailureTrigger();
        }

        assertThat(countRows("financial_transaction")).isZero();
        assertThat(countRows("idempotency_record")).isEqualTo(1);
        IdempotencyRecord record = recordByKey(key);
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getFailureCode())
                .isEqualTo("TRANSACTION_INTAKE_FAILED");
        assertThat(record.getResponseSnapshot()).isNull();
        assertThat(linkedTransactionId(record.getId())).isNull();
    }

    @Test
    void rollsBackDuplicateInsertAndFailsTheNewIdempotencyRecord() {
        UUID transactionId = UUID.randomUUID();
        TransactionCreateRequest request = request(transactionId);
        transactionIntakeService.receive(key("original"), request);
        String duplicateKey = key("duplicate");

        TransactionIntakeResult duplicate =
                transactionIntakeService.receive(duplicateKey, request);

        assertThat(duplicate)
                .isEqualTo(new TransactionIntakeResult.DuplicateTransaction(
                        transactionId
                ));
        assertThat(((TransactionIntakeResult.DuplicateTransaction) duplicate)
                .failureCode()).isEqualTo("DUPLICATE_TRANSACTION");
        assertThat(countRows("financial_transaction")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(2);

        IdempotencyRecord duplicateRecord = recordByKey(duplicateKey);
        assertThat(duplicateRecord.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(duplicateRecord.getFailureCode())
                .isEqualTo("DUPLICATE_TRANSACTION");
        assertThat(duplicateRecord.getResponseSnapshot()).isNull();
        assertThat(duplicateRecord.getFinishedAt()).isNotNull();
        assertThat(linkedTransactionId(duplicateRecord.getId())).isNull();
    }

    @Test
    void rollsBackGeneralDatabaseFailureThenFailsClaimInSeparateTransaction() {
        installFailureTrigger();
        String key = key("database-failure");
        TransactionCreateRequest request = request(
                UUID.randomUUID(),
                "force_storage_failure"
        );

        try {
            assertThatThrownBy(() -> transactionIntakeService.receive(key, request))
                    .isInstanceOf(JpaSystemException.class)
                    .satisfies(exception -> {
                        SQLException sqlException = findSqlException(exception);
                        assertThatObject(sqlException).isNotNull();
                        assertThat(sqlException.getSQLState()).isEqualTo("P0001");
                    });
        } finally {
            removeFailureTrigger();
        }

        assertThat(countRows("financial_transaction")).isZero();
        assertThat(countRows("idempotency_record")).isEqualTo(1);
        IdempotencyRecord record = recordByKey(key);
        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getFailureCode())
                .isEqualTo("TRANSACTION_INTAKE_FAILED");
        assertThat(record.getResponseSnapshot()).isNull();
        assertThat(record.getFinishedAt()).isNotNull();
        assertThat(linkedTransactionId(record.getId())).isNull();
    }

    @Test
    void rollsBackInsertedTransactionWhenLinkingToDifferentTransactionFails() {
        FinancialTransaction existing = financialTransactionRepository.saveAndFlush(
                transaction(UUID.randomUUID(), "cust_ref_existing_link")
        );
        ValidatedTransactionCommand command = command(
                UUID.randomUUID(),
                "cust_ref_link_candidate"
        );
        long recordId = acquiredRecordId(idempotencyService.claim(
                key("link-failure"),
                command.toFingerprintInput()
        ));
        IdempotencyRecord record =
                idempotencyRecordRepository.findById(recordId).orElseThrow();
        record.linkTransaction(existing);
        idempotencyRecordRepository.saveAndFlush(record);

        assertThatThrownBy(() -> transactionIntakeWriter.saveAndLink(
                recordId,
                command
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different transaction");

        assertThat(financialTransactionRepository
                .findByTransactionId(command.transactionId())).isEmpty();
        assertThat(financialTransactionRepository
                .findByTransactionId(existing.getTransactionId())).isPresent();
        assertThat(countRows("financial_transaction")).isEqualTo(1);
        assertThat(linkedTransactionId(recordId)).isEqualTo(existing.getId());
        IdempotencyRecord stored =
                idempotencyRecordRepository.findById(recordId).orElseThrow();
        assertThat(stored.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(stored.getResponseSnapshot()).isNull();
        assertThat(stored.getFinishedAt()).isNull();
    }

    private void assertNoIntakeRows() {
        assertThat(countRows("financial_transaction")).isZero();
        assertThat(countRows("idempotency_record")).isZero();
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class
        );
    }

    private Long linkedTransactionId(long recordId) {
        return jdbcTemplate.queryForObject("""
                SELECT financial_transaction_id
                FROM idempotency_record
                WHERE id = ?
                """, Long.class, recordId);
    }

    private IdempotencyRecord recordByKey(String key) {
        return idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(OPERATION_SCOPE, key)
                .orElseThrow();
    }

    private long acquiredRecordId(IdempotencyClaimResult result) {
        assertThat(result).isInstanceOf(IdempotencyClaimResult.Acquired.class);
        return ((IdempotencyClaimResult.Acquired) result).recordId();
    }

    private void installFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_transaction_intake_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.external_customer_ref = 'force_storage_failure' THEN
                        RAISE EXCEPTION 'forced transaction intake failure'
                            USING ERRCODE = 'P0001';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_fail_transaction_intake_test
                AFTER INSERT ON financial_transaction
                FOR EACH ROW
                EXECUTE FUNCTION fail_transaction_intake_test()
                """);
    }

    private void removeFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_fail_transaction_intake_test
                ON financial_transaction
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS fail_transaction_intake_test()"
        );
    }

    private void installCompletionFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_intake_completion_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.processing_status = 'COMPLETED' THEN
                        RAISE EXCEPTION 'forced intake completion failure'
                            USING ERRCODE = 'P0001';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tg_fail_intake_completion_test
                BEFORE UPDATE ON idempotency_record
                FOR EACH ROW
                EXECUTE FUNCTION fail_intake_completion_test()
                """);
    }

    private void removeCompletionFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS tg_fail_intake_completion_test
                ON idempotency_record
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS fail_intake_completion_test()"
        );
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private TransactionCreateRequest request(UUID transactionId) {
        return request(transactionId, "cust_ref_intake_integration");
    }

    private TransactionCreateRequest request(
            UUID transactionId,
            String externalCustomerRef
    ) {
        return new TransactionCreateRequest(
                transactionId.toString(),
                "ACCOUNT_TRANSFER",
                "1250000",
                "KRW",
                intakeOccurredAt().toString(),
                externalCustomerRef,
                "acct_ref_intake_integration_sender",
                "acct_ref_intake_integration_recipient",
                "MOBILE_BANKING",
                "device_ref_intake_integration"
        );
    }

    private TransactionCreateRequest copy(
            TransactionCreateRequest request,
            String transactionId,
            String amount,
            String recipientAccountRef,
            String channel
    ) {
        return new TransactionCreateRequest(
                transactionId,
                request.transactionType(),
                amount,
                request.currencyCode(),
                request.occurredAt(),
                request.externalCustomerRef(),
                request.senderAccountRef(),
                recipientAccountRef,
                channel,
                request.deviceRef()
        );
    }

    private ValidatedTransactionCommand command(
            UUID transactionId,
            String externalCustomerRef
    ) {
        return new ValidatedTransactionCommand(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                intakeOccurredAt(),
                externalCustomerRef,
                "acct_ref_writer_integration_sender",
                "acct_ref_writer_integration_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_writer_integration"
        );
    }

    private TransactionFingerprintInput fingerprintInput(
            TransactionCreateRequest request
    ) {
        return new TransactionFingerprintInput(
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

    private FinancialTransaction transaction(
            UUID transactionId,
            String externalCustomerRef
    ) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                intakeOccurredAt(),
                externalCustomerRef,
                "acct_ref_existing_integration_sender",
                "acct_ref_existing_integration_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_existing_integration"
        );
    }

    private Instant intakeOccurredAt() {
        return Instant.now()
                .minus(1, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MICROS);
    }
}
