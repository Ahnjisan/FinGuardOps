package com.aifds.backend.transaction.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.observability.TransactionIntakeMetricsFilter;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.dto.TransactionCreateResponse;
import com.aifds.backend.transaction.exception.TransactionIntakeRejectedException;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionIntakeController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransactionIntakeService transactionIntakeService;

    public TransactionIntakeController(
            TransactionIntakeService transactionIntakeService
    ) {
        this.transactionIntakeService = transactionIntakeService;
    }

    @PostMapping
    public ResponseEntity<TransactionCreateResponse> receive(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody TransactionCreateRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId,
            HttpServletRequest servletRequest
    ) {
        TransactionIntakeResult result =
                transactionIntakeService.receive(
                        idempotencyKey,
                        request,
                        traceId
                );

        if (result instanceof TransactionIntakeResult.Received received) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.ACCEPTED);
            return ResponseEntity.status(received.httpStatus()).body(
                    TransactionCreateResponse.from(
                            received.snapshot(),
                            traceId
                    )
            );
        }
        if (result instanceof TransactionIntakeResult.CompletedReplay replay) {
            markReplay(servletRequest, TransactionProcessingMetricsRecorder
                    .DuplicateResult.COMPLETED);
            return ResponseEntity.status(replay.httpStatus()).body(
                    TransactionCreateResponse.from(replay.snapshot(), traceId)
            );
        }
        if (result instanceof TransactionIntakeResult.KeyConflict) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.CONFLICT);
            TransactionIntakeMetricsFilter.markIdempotencyConflict(
                    servletRequest
            );
            throw TransactionIntakeRejectedException.keyConflict();
        }
        if (result instanceof TransactionIntakeResult.InProgress) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.IN_PROGRESS);
            TransactionIntakeMetricsFilter.markDuplicate(
                    servletRequest,
                    TransactionProcessingMetricsRecorder.DuplicateResult
                            .IN_PROGRESS
            );
            throw TransactionIntakeRejectedException.requestInProgress();
        }
        if (result instanceof TransactionIntakeResult.DuplicateTransaction) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.CONFLICT);
            throw TransactionIntakeRejectedException.duplicateTransaction();
        }
        if (result instanceof TransactionIntakeResult.PreviousFailure failure) {
            markReplay(servletRequest, TransactionProcessingMetricsRecorder
                    .DuplicateResult.FAILED);
            throw TransactionIntakeRejectedException.previousFailure(
                    failure.failureCode()
            );
        }
        if (result
                instanceof TransactionIntakeResult.ExternalRiskFailure failure) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.EXTERNAL_RISK_FAILED);
            throw TransactionIntakeRejectedException.typedFailure(
                    failure.httpStatus(),
                    failure.code(),
                    failure.message()
            );
        }
        if (result instanceof TransactionIntakeResult.ExternalRiskFailureReplay
                failure) {
            markReplay(servletRequest, TransactionProcessingMetricsRecorder
                    .DuplicateResult.FAILED);
            throw TransactionIntakeRejectedException.typedFailure(
                    failure.httpStatus(),
                    failure.code(),
                    failure.message()
            );
        }
        if (result instanceof TransactionIntakeResult.ProviderUnavailable) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.DEPENDENCY_UNAVAILABLE);
            throw TransactionIntakeRejectedException.dependencyUnavailable();
        }
        if (result instanceof TransactionIntakeResult.RuleFailure) {
            markOutcome(servletRequest, TransactionProcessingMetricsRecorder
                    .IntakeOutcome.RULE_FAILED);
            throw TransactionIntakeRejectedException.dependencyUnavailable();
        }
        throw new IllegalStateException(
                "Unsupported transaction intake result: "
                        + result.getClass().getName()
        );
    }

    private void markOutcome(
            HttpServletRequest request,
            TransactionProcessingMetricsRecorder.IntakeOutcome outcome
    ) {
        TransactionIntakeMetricsFilter.markOutcome(request, outcome);
    }

    private void markReplay(
            HttpServletRequest request,
            TransactionProcessingMetricsRecorder.DuplicateResult result
    ) {
        markOutcome(
                request,
                TransactionProcessingMetricsRecorder.IntakeOutcome
                        .IDEMPOTENT_REPLAY
        );
        TransactionIntakeMetricsFilter.markDuplicate(request, result);
    }
}
