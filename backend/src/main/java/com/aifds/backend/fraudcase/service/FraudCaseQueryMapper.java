package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCaseDetailItemResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseListItemResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class FraudCaseQueryMapper {

    public FraudCaseListItemResponse toListItem(
            FraudCase fraudCase,
            long relatedTransactionCount
    ) {
        validate(fraudCase, relatedTransactionCount);
        return new FraudCaseListItemResponse(
                fraudCase.getCaseId(),
                fraudCase.getCaseStatus(),
                fraudCase.getFinalDisposition(),
                fraudCase.getAssigneeRef(),
                relatedTransactionCount,
                fraudCase.getCreatedAt(),
                fraudCase.getLastChangedAt()
        );
    }

    public FraudCaseDetailItemResponse toDetailItem(
            FraudCase fraudCase,
            long relatedTransactionCount
    ) {
        validate(fraudCase, relatedTransactionCount);
        return new FraudCaseDetailItemResponse(
                fraudCase.getCaseId(),
                fraudCase.getCaseStatus(),
                fraudCase.getFinalDisposition(),
                fraudCase.getAssigneeRef(),
                relatedTransactionCount,
                fraudCase.getCreatedAt(),
                fraudCase.getReviewStartedAt(),
                fraudCase.getClosedAt(),
                fraudCase.getLastChangedAt(),
                fraudCase.getConcurrencyVersion()
        );
    }

    private void validate(FraudCase fraudCase, long count) {
        Objects.requireNonNull(fraudCase, "fraudCase must not be null");
        if (count < 0) {
            throw new IllegalArgumentException(
                    "relatedTransactionCount must not be negative"
            );
        }
    }
}
