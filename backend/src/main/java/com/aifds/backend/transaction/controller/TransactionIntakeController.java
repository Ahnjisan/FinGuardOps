package com.aifds.backend.transaction.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.transaction.dto.TransactionCreateRequest;
import com.aifds.backend.transaction.dto.TransactionCreateResponse;
import com.aifds.backend.transaction.exception.TransactionIntakeRejectedException;
import com.aifds.backend.transaction.service.TransactionIntakeResult;
import com.aifds.backend.transaction.service.TransactionIntakeService;
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
            String traceId
    ) {
        TransactionIntakeResult result =
                transactionIntakeService.receive(idempotencyKey, request);

        if (result instanceof TransactionIntakeResult.Received received) {
            return ResponseEntity.status(received.httpStatus()).body(
                    TransactionCreateResponse.from(
                            received.snapshot(),
                            traceId
                    )
            );
        }
        if (result instanceof TransactionIntakeResult.CompletedReplay replay) {
            return ResponseEntity.status(replay.httpStatus()).body(
                    TransactionCreateResponse.from(replay.snapshot(), traceId)
            );
        }
        if (result instanceof TransactionIntakeResult.KeyConflict) {
            throw TransactionIntakeRejectedException.keyConflict();
        }
        if (result instanceof TransactionIntakeResult.InProgress) {
            throw TransactionIntakeRejectedException.requestInProgress();
        }
        if (result instanceof TransactionIntakeResult.DuplicateTransaction) {
            throw TransactionIntakeRejectedException.duplicateTransaction();
        }
        if (result instanceof TransactionIntakeResult.PreviousFailure failure) {
            throw TransactionIntakeRejectedException.previousFailure(
                    failure.failureCode()
            );
        }
        throw new IllegalStateException(
                "Unsupported transaction intake result: "
                        + result.getClass().getName()
        );
    }
}
