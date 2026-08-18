package com.aifds.backend.fraudcase.entity;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "case_transaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_case_transaction_case_transaction",
                columnNames = {
                        "fraud_case_id",
                        "financial_transaction_id"
                }
        )
)
public class CaseTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "fraud_case_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_case_transaction_case")
    )
    private FraudCase fraudCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "financial_transaction_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_case_transaction_transaction"
            )
    )
    private FinancialTransaction financialTransaction;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected CaseTransaction() {
    }

    private CaseTransaction(
            FraudCase fraudCase,
            FinancialTransaction financialTransaction,
            Instant linkedAt
    ) {
        this.fraudCase = Objects.requireNonNull(
                fraudCase,
                "fraudCase must not be null"
        );
        this.financialTransaction = Objects.requireNonNull(
                financialTransaction,
                "financialTransaction must not be null"
        );
        this.linkedAt = requireMicrosecondInstant(linkedAt);
    }

    public static CaseTransaction link(
            FraudCase fraudCase,
            FinancialTransaction financialTransaction,
            Instant linkedAt
    ) {
        return new CaseTransaction(
                fraudCase,
                financialTransaction,
                linkedAt
        );
    }

    public boolean belongsTo(
            FraudCase expectedCase,
            FinancialTransaction expectedTransaction
    ) {
        return sameCase(expectedCase) && sameTransaction(expectedTransaction);
    }

    private boolean sameCase(FraudCase expectedCase) {
        if (expectedCase == null) {
            return false;
        }
        if (fraudCase == expectedCase) {
            return true;
        }
        return fraudCase.getCaseId() != null
                && fraudCase.getCaseId().equals(expectedCase.getCaseId());
    }

    private boolean sameTransaction(
            FinancialTransaction expectedTransaction
    ) {
        if (expectedTransaction == null) {
            return false;
        }
        if (financialTransaction == expectedTransaction) {
            return true;
        }
        return financialTransaction.getTransactionId() != null
                && financialTransaction.getTransactionId().equals(
                expectedTransaction.getTransactionId()
        );
    }

    private static Instant requireMicrosecondInstant(Instant value) {
        Instant validated = Objects.requireNonNull(
                value,
                "linkedAt must not be null"
        );
        if (validated.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "linkedAt must have microsecond precision"
            );
        }
        return validated;
    }

    public Long getId() {
        return id;
    }

    public FraudCase getFraudCase() {
        return fraudCase;
    }

    public FinancialTransaction getFinancialTransaction() {
        return financialTransaction;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
