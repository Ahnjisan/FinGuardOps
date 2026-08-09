package com.aifds.backend.persistence;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RepositoryPersistenceIntegrationTest extends PostgresqlIntegrationTestSupport {

    private static final String TRANSACTION_OPERATION_SCOPE = "POST:/api/v1/transactions";

    @Autowired
    private FinancialTransactionRepository financialTransactionRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void savesAndFindsTransactionByTransactionId() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction saved = financialTransactionRepository.saveAndFlush(
                transaction(transactionId)
        );
        entityManager.clear();

        FinancialTransaction found = financialTransactionRepository.findByTransactionId(transactionId)
                .orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getTransactionId()).isEqualTo(transactionId);
        assertThat(found.getAmount()).isEqualByComparingTo("1250000.0000");
        assertThat(found.getProcessingStatus()).isEqualTo(TransactionProcessingStatus.RECEIVED);
    }

    @Test
    void returnsEmptyWhenTransactionIdDoesNotExist() {
        assertThat(financialTransactionRepository.findByTransactionId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void savesAndFindsInProgressIdempotencyRecordByScopeAndKey() {
        String idempotencyKey = "repo-in-progress-" + UUID.randomUUID();
        IdempotencyRecord saved = idempotencyRecordRepository.saveAndFlush(
                IdempotencyRecord.inProgress(
                        TRANSACTION_OPERATION_SCOPE,
                        idempotencyKey,
                        "a".repeat(64)
                )
        );
        entityManager.clear();

        IdempotencyRecord found = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(
                        TRANSACTION_OPERATION_SCOPE,
                        idempotencyKey
                )
                .orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getProcessingStatus()).isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(found.getFinancialTransaction()).isNull();
        assertThat(found.getExpiresAt())
                .isEqualTo(found.getCreatedAt().plus(24, ChronoUnit.HOURS));
    }

    @Test
    void allowsSameIdempotencyKeyInDifferentOperationScopes() {
        String idempotencyKey = "shared-key-" + UUID.randomUUID();
        String otherOperationScope = "POST:/api/v1/other-operation";

        idempotencyRecordRepository.saveAndFlush(
                IdempotencyRecord.inProgress(
                        TRANSACTION_OPERATION_SCOPE,
                        idempotencyKey,
                        "b".repeat(64)
                )
        );
        idempotencyRecordRepository.saveAndFlush(
                IdempotencyRecord.inProgress(
                        otherOperationScope,
                        idempotencyKey,
                        "c".repeat(64)
                )
        );

        assertThat(idempotencyRecordRepository.findByOperationScopeAndIdempotencyKey(
                TRANSACTION_OPERATION_SCOPE,
                idempotencyKey
        )).isPresent();
        assertThat(idempotencyRecordRepository.findByOperationScopeAndIdempotencyKey(
                otherOperationScope,
                idempotencyKey
        )).isPresent();
    }

    @Test
    void rejectsDuplicateTransactionIdWithPostgresqlUniqueConstraint() {
        UUID transactionId = UUID.randomUUID();
        financialTransactionRepository.saveAndFlush(transaction(transactionId));

        assertPersistenceConstraintViolation(
                () -> financialTransactionRepository.saveAndFlush(transaction(transactionId)),
                "23505",
                "uq_financial_transaction_transaction_id"
        );
    }

    @Test
    void rejectsDuplicateScopeAndIdempotencyKeyWithPostgresqlUniqueConstraint() {
        String idempotencyKey = "duplicate-repo-key-" + UUID.randomUUID();
        idempotencyRecordRepository.saveAndFlush(
                IdempotencyRecord.inProgress(
                        TRANSACTION_OPERATION_SCOPE,
                        idempotencyKey,
                        "d".repeat(64)
                )
        );

        assertPersistenceConstraintViolation(
                () -> idempotencyRecordRepository.saveAndFlush(
                        IdempotencyRecord.inProgress(
                                TRANSACTION_OPERATION_SCOPE,
                                idempotencyKey,
                                "e".repeat(64)
                        )
                ),
                "23505",
                "uq_idempotency_record_scope_key"
        );
    }

    @Test
    @Transactional
    void savesAndLoadsTransactionRelationshipThroughIdempotencyRepository() {
        UUID transactionId = UUID.randomUUID();
        FinancialTransaction transaction = financialTransactionRepository.saveAndFlush(
                transaction(transactionId)
        );
        String idempotencyKey = "relationship-key-" + UUID.randomUUID();
        IdempotencyRecord saved = idempotencyRecordRepository.saveAndFlush(
                IdempotencyRecord.inProgress(
                        TRANSACTION_OPERATION_SCOPE,
                        idempotencyKey,
                        "f".repeat(64)
                )
        );
        saved.complete(
                transaction,
                objectMapper.createObjectNode()
                        .put("transactionId", transactionId.toString()),
                saved.getCreatedAt()
        );
        idempotencyRecordRepository.saveAndFlush(saved);
        Long transactionPk = transaction.getId();
        entityManager.clear();

        IdempotencyRecord found = idempotencyRecordRepository
                .findByOperationScopeAndIdempotencyKey(
                        TRANSACTION_OPERATION_SCOPE,
                        idempotencyKey
                )
                .orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getFinancialTransaction().getId()).isEqualTo(transactionPk);
        assertThat(found.getFinancialTransaction().getTransactionId()).isEqualTo(transactionId);
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.now().minus(1, ChronoUnit.MINUTES),
                "cust_ref_repository",
                "acct_ref_repository_sender",
                "acct_ref_repository_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_repository"
        );
    }

    private void assertPersistenceConstraintViolation(
            Runnable operation,
            String expectedSqlState,
            String expectedConstraint
    ) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> {
                    SQLException sqlException = findSqlException(exception);

                    assertThatObject(sqlException).isNotNull();
                    assertThat(sqlException.getSQLState()).isEqualTo(expectedSqlState);
                    assertThat(sqlException.getMessage()).contains(expectedConstraint);
                });
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
}
