package com.aifds.backend.fraudcase.repository;

import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.query.FraudCaseQueryCriteria;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class FraudCaseSpecifications {

    private FraudCaseSpecifications() {
    }

    public static Specification<FraudCase> from(
            FraudCaseQueryCriteria criteria
    ) {
        List<Specification<FraudCase>> specifications = new ArrayList<>();

        if (criteria.caseStatus() != null) {
            specifications.add((root, query, builder) -> builder.equal(
                    root.get("caseStatus"),
                    criteria.caseStatus()
            ));
        }
        if (criteria.finalDisposition() != null) {
            specifications.add((root, query, builder) -> builder.equal(
                    root.get("finalDisposition"),
                    criteria.finalDisposition()
            ));
        }
        if (criteria.assigneeRef() != null) {
            specifications.add((root, query, builder) -> builder.equal(
                    root.get("assigneeRef"),
                    criteria.assigneeRef()
            ));
        }
        if (criteria.createdAtFrom() != null) {
            specifications.add((root, query, builder) ->
                    builder.greaterThanOrEqualTo(
                            root.get("createdAt"),
                            criteria.createdAtFrom()
                    )
            );
        }
        if (criteria.createdAtTo() != null) {
            specifications.add((root, query, builder) -> builder.lessThan(
                    root.get("createdAt"),
                    criteria.createdAtTo()
            ));
        }
        if (criteria.lastChangedAtFrom() != null) {
            specifications.add((root, query, builder) ->
                    builder.greaterThanOrEqualTo(
                            root.get("lastChangedAt"),
                            criteria.lastChangedAtFrom()
                    )
            );
        }
        if (criteria.lastChangedAtTo() != null) {
            specifications.add((root, query, builder) -> builder.lessThan(
                    root.get("lastChangedAt"),
                    criteria.lastChangedAtTo()
            ));
        }
        if (criteria.transactionId() != null) {
            specifications.add(transactionExists(criteria));
        }
        return Specification.allOf(specifications);
    }

    private static Specification<FraudCase> transactionExists(
            FraudCaseQueryCriteria criteria
    ) {
        return (root, query, builder) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<CaseTransaction> link = subquery.from(CaseTransaction.class);
            subquery.select(builder.literal(1L));
            subquery.where(
                    builder.equal(
                            link.get("fraudCase").get("id"),
                            root.get("id")
                    ),
                    builder.equal(
                            link.get("financialTransaction")
                                    .get("transactionId"),
                            criteria.transactionId()
                    )
            );
            return builder.exists(subquery);
        };
    }
}
