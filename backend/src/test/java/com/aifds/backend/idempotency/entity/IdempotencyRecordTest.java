package com.aifds.backend.idempotency.entity;

import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordTest {

    private static final String OPERATION_SCOPE = "POST:/api/v1/transactions";
    private static final Instant FINISHED_AT = Instant.parse("2026-07-28T02:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void startsInProgressWithoutTerminalFields() {
        IdempotencyRecord record = inProgress();

        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.IN_PROGRESS);
        assertThat(record.getFinancialTransaction()).isNull();
        assertThat(record.getResponseSnapshot()).isNull();
        assertThat(record.getFailureCode()).isNull();
        assertThat(record.getFinishedAt()).isNull();
    }

    @Test
    void completesFromInProgressAndDefensivelyCopiesSnapshot() {
        IdempotencyRecord record = inProgress();
        FinancialTransaction transaction = transaction();
        ObjectNode snapshot = objectMapper.createObjectNode()
                .put("transactionId", transaction.getTransactionId().toString());

        record.complete(transaction, snapshot, FINISHED_AT);
        snapshot.put("mutatedAfterTransition", true);
        ObjectNode returnedSnapshot = (ObjectNode) record.getResponseSnapshot();
        returnedSnapshot.put("mutatedThroughGetter", true);

        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.COMPLETED);
        assertThat(record.getFinancialTransaction()).isSameAs(transaction);
        assertThat(record.getResponseSnapshot().has("mutatedAfterTransition")).isFalse();
        assertThat(record.getResponseSnapshot().has("mutatedThroughGetter")).isFalse();
        assertThat(record.getFailureCode()).isNull();
        assertThat(record.getFinishedAt()).isEqualTo(FINISHED_AT);
    }

    @Test
    void failsFromInProgress() {
        IdempotencyRecord record = inProgress();

        record.fail("DEPENDENCY_TIMEOUT", FINISHED_AT);

        assertThat(record.getProcessingStatus())
                .isEqualTo(IdempotencyProcessingStatus.FAILED);
        assertThat(record.getResponseSnapshot()).isNull();
        assertThat(record.getFailureCode()).isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(record.getFinishedAt()).isEqualTo(FINISHED_AT);
    }

    @Test
    void rejectsTransitionsFromCompletedAndFailedStates() {
        IdempotencyRecord completed = inProgress();
        completed.complete(
                transaction(),
                objectMapper.createObjectNode().put("result", "completed"),
                FINISHED_AT
        );
        IdempotencyRecord failed = inProgress();
        failed.fail("DEPENDENCY_TIMEOUT", FINISHED_AT);

        assertThatThrownBy(() -> completed.fail("INTERNAL_ERROR", FINISHED_AT.plusSeconds(1)))
                .isInstanceOf(IdempotencyStateTransitionNotAllowedException.class);
        assertThatThrownBy(() -> completed.complete(
                transaction(),
                objectMapper.createObjectNode(),
                FINISHED_AT.plusSeconds(1)
        )).isInstanceOf(IdempotencyStateTransitionNotAllowedException.class);
        assertThatThrownBy(() -> failed.fail("INTERNAL_ERROR", FINISHED_AT.plusSeconds(1)))
                .isInstanceOf(IdempotencyStateTransitionNotAllowedException.class);
        assertThatThrownBy(() -> failed.complete(
                transaction(),
                objectMapper.createObjectNode(),
                FINISHED_AT.plusSeconds(1)
        )).isInstanceOf(IdempotencyStateTransitionNotAllowedException.class);
    }

    @Test
    void rejectsMissingOrNonObjectCompletionSnapshot() {
        assertThatThrownBy(() -> inProgress().complete(transaction(), null, FINISHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inProgress().complete(
                transaction(),
                objectMapper.createArrayNode(),
                FINISHED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inProgress().complete(
                null,
                objectMapper.createObjectNode(),
                FINISHED_AT
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsTraceIdAtAnySnapshotDepthIncludingArrays() {
        ObjectNode topLevel = objectMapper.createObjectNode().put("traceId", "top-level");
        ObjectNode nestedObject = objectMapper.createObjectNode();
        nestedObject.set(
                "result",
                objectMapper.createObjectNode().put("traceId", "nested-object")
        );
        ObjectNode nestedArray = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        items.add(objectMapper.createObjectNode().put("traceId", "array-object"));
        nestedArray.set("items", items);

        for (ObjectNode snapshot : List.of(topLevel, nestedObject, nestedArray)) {
            assertThatThrownBy(() -> inProgress().complete(
                    transaction(),
                    snapshot,
                    FINISHED_AT
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("traceId");
        }
    }

    @Test
    void rejectsFailureCodesOutsideApprovedSafeFormat() {
        for (String failureCode : List.of(
                "",
                " dependency_timeout",
                "DEPENDENCY_TIMEOUT ",
                "dependency_timeout",
                "1INVALID",
                "INVALID-CODE",
                "A".repeat(65)
        )) {
            assertThatThrownBy(() -> inProgress().fail(failureCode, FINISHED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> inProgress().fail(null, FINISHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IdempotencyRecord inProgress() {
        return IdempotencyRecord.inProgress(
                OPERATION_SCOPE,
                "unit-test-key",
                "a".repeat(64)
        );
    }

    private FinancialTransaction transaction() {
        return new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1250000"),
                "KRW",
                Instant.parse("2026-07-28T01:00:00Z"),
                "cust_ref_unit",
                "acct_ref_unit_sender",
                "acct_ref_unit_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_unit"
        );
    }
}
