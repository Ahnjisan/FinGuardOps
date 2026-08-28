package com.aifds.recovery.idempotency;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryCandidate;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryResult;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IdempotencyRecoveryCommandRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IdempotencyRecoveryService recoveryService;
    private IdempotencyRecoveryCommandRunner runner;

    @BeforeEach
    void setUp() {
        recoveryService = mock(IdempotencyRecoveryService.class);
        runner = new IdempotencyRecoveryCommandRunner(
                recoveryService,
                objectMapper
        );
    }

    @Test
    void inspectCallsOneBoundedQueryAndWritesCandidatesAndSummaryJsonl()
            throws Exception {
        UUID transactionId = UUID.fromString(
                "11111111-1111-4111-8111-111111111111"
        );
        Instant firstUpdatedAt = Instant.parse("2026-08-28T01:02:03Z");
        Instant secondUpdatedAt = Instant.parse("2026-08-28T01:02:04Z");
        when(recoveryService.findLongRunningCandidates(
                Duration.ofMinutes(45),
                25
        )).thenReturn(List.of(
                new IdempotencyRecoveryCandidate(
                        11,
                        transactionId,
                        firstUpdatedAt
                ),
                new IdempotencyRecoveryCandidate(
                        12,
                        null,
                        secondUpdatedAt
                )
        ));

        IdempotencyRecoveryCommandResult result = runner.run(
                new IdempotencyRecoveryCommandArguments(
                        IdempotencyRecoveryCommandArguments.Action.INSPECT,
                        Duration.ofMinutes(45),
                        25,
                        null
                )
        );

        assertThat(result.exitCode()).isZero();
        assertThat(result.standardOutputLines()).hasSize(3);
        JsonNode first = objectMapper.readTree(
                result.standardOutputLines().get(0)
        );
        assertThat(first.get("type").textValue()).isEqualTo("candidate");
        assertThat(first.get("action").textValue()).isEqualTo("inspect");
        assertThat(first.get("recordId").longValue()).isEqualTo(11);
        assertThat(first.get("transactionId").textValue())
                .isEqualTo(transactionId.toString());
        assertThat(first.get("updatedAt").textValue())
                .isEqualTo("2026-08-28T01:02:03Z");
        JsonNode second = objectMapper.readTree(
                result.standardOutputLines().get(1)
        );
        assertThat(second.get("transactionId").isNull()).isTrue();
        JsonNode summary = objectMapper.readTree(
                result.standardOutputLines().get(2)
        );
        assertThat(summary.get("type").textValue()).isEqualTo("summary");
        assertThat(summary.get("processedCount").intValue()).isEqualTo(2);
        verify(recoveryService, times(1)).findLongRunningCandidates(
                Duration.ofMinutes(45),
                25
        );
        verifyNoMoreInteractions(recoveryService);
    }

    @Test
    void emptyInspectIsSuccessfulAndWritesOnlyZeroSummary() throws Exception {
        when(recoveryService.findLongRunningCandidates(
                Duration.ofMinutes(30),
                50
        )).thenReturn(List.of());

        IdempotencyRecoveryCommandResult result = runner.run(
                inspectArguments()
        );

        assertThat(result.exitCode()).isZero();
        assertThat(result.standardOutputLines()).hasSize(1);
        assertThat(objectMapper.readTree(result.standardOutputLines().get(0))
                .get("processedCount").intValue()).isZero();
    }

    @Test
    void recoverUsesFixedSystemActorExactlyOnceAndMapsRecoveredResult()
            throws Exception {
        UUID transactionId = UUID.fromString(
                "22222222-2222-4222-8222-222222222222"
        );
        when(recoveryService.recover(
                123,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).thenReturn(new IdempotencyRecoveryResult(
                123,
                transactionId,
                IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP,
                IdempotencyRecoveryAuditResult.RECOVERED
        ));

        IdempotencyRecoveryCommandResult result = runner.run(
                recoverArguments(123)
        );

        assertThat(result.exitCode()).isZero();
        JsonNode output = objectMapper.readTree(
                result.standardOutputLines().get(0)
        );
        assertThat(output.get("recordId").longValue()).isEqualTo(123);
        assertThat(output.get("transactionId").textValue())
                .isEqualTo(transactionId.toString());
        assertThat(output.get("decision").textValue())
                .isEqualTo("RECOVERABLE_COMPLETION_GAP");
        assertThat(output.get("auditResult").textValue())
                .isEqualTo("RECOVERED");
        verify(recoveryService, times(1)).recover(
                123,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        verifyNoMoreInteractions(recoveryService);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdempotencyRecoveryDecision.class,
            names = {"ALREADY_TERMINAL", "PROCESSING_INDETERMINATE"}
    )
    void typedRejectionMapsToJsonlAndExitThree(
            IdempotencyRecoveryDecision decision
    ) throws Exception {
        when(recoveryService.recover(
                456,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).thenReturn(new IdempotencyRecoveryResult(
                456,
                null,
                decision,
                IdempotencyRecoveryAuditResult.REJECTED
        ));

        IdempotencyRecoveryCommandResult result = runner.run(
                recoverArguments(456)
        );

        assertThat(result.exitCode()).isEqualTo(3);
        JsonNode output = objectMapper.readTree(
                result.standardOutputLines().get(0)
        );
        assertThat(output.get("transactionId").isNull()).isTrue();
        assertThat(output.get("decision").textValue())
                .isEqualTo(decision.name());
        assertThat(output.get("auditResult").textValue())
                .isEqualTo("REJECTED");
        verify(recoveryService, times(1)).recover(
                456,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        verifyNoMoreInteractions(recoveryService);
    }

    @Test
    void internalFailureIsNotRetriedOrConvertedToTypedResult() {
        RuntimeException failure = new RuntimeException("password=secret");
        when(recoveryService.recover(
                789,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        )).thenThrow(failure);

        assertThatThrownBy(() -> runner.run(recoverArguments(789)))
                .isSameAs(failure);
        verify(recoveryService, times(1)).recover(
                789,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID
        );
        verifyNoMoreInteractions(recoveryService);
    }

    private IdempotencyRecoveryCommandArguments inspectArguments() {
        return new IdempotencyRecoveryCommandArguments(
                IdempotencyRecoveryCommandArguments.Action.INSPECT,
                Duration.ofMinutes(30),
                50,
                null
        );
    }

    private IdempotencyRecoveryCommandArguments recoverArguments(long id) {
        return new IdempotencyRecoveryCommandArguments(
                IdempotencyRecoveryCommandArguments.Action.RECOVER,
                null,
                0,
                id
        );
    }
}
