package com.aifds.recovery.idempotency;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryCandidate;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class IdempotencyRecoveryCommandRunner {

    private final IdempotencyRecoveryService recoveryService;
    private final ObjectMapper objectMapper;

    public IdempotencyRecoveryCommandRunner(
            IdempotencyRecoveryService recoveryService,
            ObjectMapper objectMapper
    ) {
        this.recoveryService = recoveryService;
        this.objectMapper = objectMapper;
    }

    public IdempotencyRecoveryCommandResult run(
            IdempotencyRecoveryCommandArguments arguments
    ) {
        return switch (arguments.action()) {
            case INSPECT -> inspect(arguments);
            case RECOVER -> recover(arguments);
        };
    }

    private IdempotencyRecoveryCommandResult inspect(
            IdempotencyRecoveryCommandArguments arguments
    ) {
        List<IdempotencyRecoveryCandidate> candidates = recoveryService
                .findLongRunningCandidates(
                        arguments.threshold(),
                        arguments.pageSize()
                );
        List<String> lines = new ArrayList<>(candidates.size() + 1);
        for (IdempotencyRecoveryCandidate candidate : candidates) {
            ObjectNode output = base("candidate", "inspect");
            output.put("recordId", candidate.idempotencyRecordId());
            if (candidate.transactionId() == null) {
                output.putNull("transactionId");
            } else {
                output.put(
                        "transactionId",
                        candidate.transactionId().toString()
                );
            }
            output.put(
                    "updatedAt",
                    DateTimeFormatter.ISO_INSTANT.format(
                            candidate.updatedAt()
                    )
            );
            lines.add(toJson(output));
        }
        ObjectNode summary = base("summary", "inspect");
        summary.put("processedCount", candidates.size());
        lines.add(toJson(summary));
        return new IdempotencyRecoveryCommandResult(0, lines);
    }

    private IdempotencyRecoveryCommandResult recover(
            IdempotencyRecoveryCommandArguments arguments
    ) {
        IdempotencyRecoveryResult result = recoveryService.recover(
                arguments.recordId(),
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        ObjectNode output = base("result", "recover");
        output.put("recordId", result.idempotencyRecordId());
        if (result.transactionId() == null) {
            output.putNull("transactionId");
        } else {
            output.put("transactionId", result.transactionId().toString());
        }
        output.put("decision", result.decision().name());
        output.put("auditResult", result.auditResult().name());
        int exitCode = result.auditResult()
                == IdempotencyRecoveryAuditResult.RECOVERED ? 0 : 3;
        return new IdempotencyRecoveryCommandResult(
                exitCode,
                List.of(toJson(output))
        );
    }

    private ObjectNode base(String type, String action) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("type", type);
        output.put("action", action);
        return output;
    }

    private String toJson(ObjectNode output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recovery output encoding failed");
        }
    }
}
