package com.aifds.backend.fraudcase.entity;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CaseTransactionTest {

    private static final Instant LINKED_AT =
            Instant.parse("2026-08-18T02:00:00.123456Z");

    @Test
    void ownsExactCaseAndTransactionRelationship() {
        FraudCase fraudCase = FraudCase.open(
                UUID.randomUUID(),
                LINKED_AT
        );
        FinancialTransaction transaction = transaction(UUID.randomUUID());

        CaseTransaction link = CaseTransaction.link(
                fraudCase,
                transaction,
                LINKED_AT
        );

        assertThat(link.getFraudCase()).isSameAs(fraudCase);
        assertThat(link.getFinancialTransaction()).isSameAs(transaction);
        assertThat(link.getLinkedAt()).isEqualTo(LINKED_AT);
        assertThat(link.belongsTo(fraudCase, transaction)).isTrue();
    }

    @Test
    void rejectsMissingRelationshipOrLinkedAt() {
        FraudCase fraudCase = FraudCase.open(
                UUID.randomUUID(),
                LINKED_AT
        );
        FinancialTransaction transaction = transaction(UUID.randomUUID());

        assertThatNullPointerException().isThrownBy(() ->
                CaseTransaction.link(null, transaction, LINKED_AT)
        );
        assertThatNullPointerException().isThrownBy(() ->
                CaseTransaction.link(fraudCase, null, LINKED_AT)
        );
        assertThatNullPointerException().isThrownBy(() ->
                CaseTransaction.link(fraudCase, transaction, null)
        );
    }

    @Test
    void rejectsLinkedAtBeyondPostgresqlMicrosecondPrecision() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                CaseTransaction.link(
                        FraudCase.open(UUID.randomUUID(), LINKED_AT),
                        transaction(UUID.randomUUID()),
                        Instant.parse("2026-08-18T02:00:00.123456789Z")
                )
        ).withMessage("linkedAt must have microsecond precision");
    }

    private FinancialTransaction transaction(UUID transactionId) {
        return new FinancialTransaction(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                BigDecimal.valueOf(10_000),
                "KRW",
                Instant.parse("2026-08-18T01:00:00Z"),
                "customer_ref",
                "sender_ref",
                "recipient_ref",
                TransactionChannel.MOBILE_BANKING,
                "device_ref"
        );
    }
}
