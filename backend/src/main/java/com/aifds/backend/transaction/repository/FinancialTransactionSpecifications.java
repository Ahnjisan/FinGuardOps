package com.aifds.backend.transaction.repository;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.query.TransactionQueryCriteria;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class FinancialTransactionSpecifications {

    private FinancialTransactionSpecifications() {
    }

    public static Specification<FinancialTransaction> from(
            TransactionQueryCriteria criteria
    ) {
        List<Specification<FinancialTransaction>> specifications =
                new ArrayList<>();

        if (criteria.occurredAtFrom() != null) {
            specifications.add((root, query, builder) ->
                    builder.greaterThanOrEqualTo(
                            root.get("occurredAt"),
                            criteria.occurredAtFrom()
                    )
            );
        }
        if (criteria.occurredAtTo() != null) {
            specifications.add((root, query, builder) ->
                    builder.lessThan(
                            root.get("occurredAt"),
                            criteria.occurredAtTo()
                    )
            );
        }
        if (criteria.transactionType() != null) {
            specifications.add((root, query, builder) ->
                    builder.equal(
                            root.get("transactionType"),
                            criteria.transactionType()
                    )
            );
        }
        if (criteria.processingStatus() != null) {
            specifications.add((root, query, builder) ->
                    builder.equal(
                            root.get("processingStatus"),
                            criteria.processingStatus()
                    )
            );
        }
        if (criteria.externalCustomerRef() != null) {
            specifications.add((root, query, builder) ->
                    builder.equal(
                            root.get("externalCustomerRef"),
                            criteria.externalCustomerRef()
                    )
            );
        }
        if (criteria.accountRef() != null) {
            specifications.add((root, query, builder) ->
                    builder.or(
                            builder.equal(
                                    root.get("senderAccountRef"),
                                    criteria.accountRef()
                            ),
                            builder.equal(
                                    root.get("recipientAccountRef"),
                                    criteria.accountRef()
                            )
                    )
            );
        }
        return Specification.allOf(specifications);
    }
}
