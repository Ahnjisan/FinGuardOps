package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class FraudCaseWorkflowMapper {

    public FraudCaseMutationResponse toResponse(
            FraudCase fraudCase,
            String traceId
    ) {
        FraudCase source = Objects.requireNonNull(
                fraudCase,
                "fraudCase must not be null"
        );
        return new FraudCaseMutationResponse(
                source.getCaseId(),
                source.getCaseStatus(),
                source.getFinalDisposition(),
                source.getAssigneeRef(),
                source.getReviewStartedAt(),
                source.getClosedAt(),
                source.getLastChangedAt(),
                source.getConcurrencyVersion(),
                traceId
        );
    }
}
