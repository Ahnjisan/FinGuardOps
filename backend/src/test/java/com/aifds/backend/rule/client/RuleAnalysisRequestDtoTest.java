package com.aifds.backend.rule.client;

import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleTransactionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleTransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class RuleAnalysisRequestDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsCutoffMismatchBeforeAnyHttpCall() {
        RuleAnalysisRequest valid = RuleAnalysisClientTestFixtures.request(mapper);

        assertThatThrownBy(() -> new RuleAnalysisRequest(
                valid.evaluationCutoffAt().plusSeconds(1),
                valid.transaction(),
                valid.behaviorEvents(),
                valid.ruleVersions()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluationCutoffAt");
    }

    @Test
    void rejectsNonCanonicalAmountAndExcessTimestampPrecision() {
        RuleAnalysisRequest valid = RuleAnalysisClientTestFixtures.request(mapper);
        RuleTransactionSnapshotRequest transaction = valid.transaction();

        assertThatThrownBy(() -> new RuleTransactionSnapshotRequest(
                transaction.transactionId(),
                RuleTransactionType.ACCOUNT_TRANSFER,
                "012000000",
                "KRW",
                Instant.parse("2026-07-23T12:00:00.000000001Z"),
                transaction.externalCustomerRef(),
                transaction.senderAccountRef(),
                transaction.recipientAccountRef(),
                transaction.deviceRef()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RuleTransactionSnapshotRequest(
                transaction.transactionId(),
                RuleTransactionType.ACCOUNT_TRANSFER,
                transaction.amount(),
                "KRW",
                Instant.parse("2026-07-23T12:00:00.000000001Z"),
                transaction.externalCustomerRef(),
                transaction.senderAccountRef(),
                transaction.recipientAccountRef(),
                transaction.deviceRef()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
    }

    @Test
    void rejectsAnEmptyRuleVersionSnapshot() {
        RuleAnalysisRequest valid = RuleAnalysisClientTestFixtures.request(mapper);

        assertThatThrownBy(() -> new RuleAnalysisRequest(
                valid.evaluationCutoffAt(),
                valid.transaction(),
                List.of(),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleVersions");
    }

    @Test
    void nontransferV4SnapshotRequiresNoRecipientWhileTransfersRequireOne() {
        RuleTransactionSnapshotRequest sample = RuleAnalysisClientTestFixtures
                .request(mapper).transaction();
        for (RuleTransactionType type : List.of(RuleTransactionType.ATM_WITHDRAWAL,
                RuleTransactionType.LOAN_DISBURSED)) {
            var nontransfer = new RuleTransactionSnapshotRequest(sample.transactionId(),
                    type, sample.amount(), sample.currencyCode(), sample.occurredAt(),
                    sample.externalCustomerRef(), sample.senderAccountRef(), null,
                    sample.deviceRef());
            assertThat(nontransfer.recipientAccountRef()).isNull();
            assertThatThrownBy(() -> new RuleTransactionSnapshotRequest(
                    sample.transactionId(), type, sample.amount(), sample.currencyCode(),
                    sample.occurredAt(), sample.externalCustomerRef(), sample.senderAccountRef(),
                    sample.recipientAccountRef(), sample.deviceRef()))
                    .hasMessageContaining("must be null");
        }
        assertThatThrownBy(() -> new RuleTransactionSnapshotRequest(
                sample.transactionId(), RuleTransactionType.ACCOUNT_TRANSFER,
                sample.amount(), sample.currencyCode(), sample.occurredAt(),
                sample.externalCustomerRef(), sample.senderAccountRef(), null,
                sample.deviceRef())).hasMessageContaining("must not be null");
    }
}
