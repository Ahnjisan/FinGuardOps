package com.aifds.backend.detection.service;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.rule.client.dto.RuleBehaviorEventType;
import com.aifds.backend.rule.client.dto.RuleTransactionType;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleAnalysisSnapshotAssemblerTest {

    private static final Instant CUTOFF =
            Instant.parse("2026-08-13T01:00:00Z");

    @Mock
    private RuleVersionRepository ruleVersionRepository;
    @Mock
    private BehaviorEventRepository behaviorEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuleAnalysisSnapshotAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new RuleAnalysisSnapshotAssembler(
                ruleVersionRepository,
                behaviorEventRepository
        );
    }

    @Test
    void createsImmutableTransactionAndRuleSnapshotsWithoutBehaviorLookup() {
        FinancialTransaction transaction = transfer(CUTOFF);
        ObjectNode condition = amountCondition();
        RuleVersion r001 = publishedVersion(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                condition
        );
        when(ruleVersionRepository.findAllExecutableVersions(CUTOFF))
                .thenReturn(new ArrayList<>(List.of(r001)));

        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                assembler.assemble(transaction);

        assertThat(snapshot.request().evaluationCutoffAt()).isEqualTo(CUTOFF);
        assertThat(snapshot.request().transaction().transactionType())
                .isEqualTo(RuleTransactionType.ACCOUNT_TRANSFER);
        assertThat(snapshot.request().transaction().amount())
                .isEqualTo("10000000");
        assertThat(snapshot.request().behaviorEvents()).isEmpty();
        assertThat(snapshot.request().ruleVersions()).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.ruleCode()).isEqualTo(
                            RuleV1ContractRegistry
                                    .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                    );
                    assertThat(rule.conditionDefinition())
                            .isEqualTo(condition);
                });
        assertThat(snapshot.ruleSetVersion()).matches("[0-9a-f]{64}");
        verify(behaviorEventRepository, never()).findForRuleEvaluation(
                any(), any(), any(), any(), any()
        );

        condition.put("amountThreshold", "99999999");
        ObjectNode returnedCondition = (ObjectNode) snapshot.request()
                .ruleVersions().get(0).conditionDefinition();
        returnedCondition.put("amountThreshold", "1");
        assertThat(snapshot.request().ruleVersions().get(0)
                .conditionDefinition().path("amountThreshold").textValue())
                .isEqualTo("10000000");
        assertThatThrownBy(() -> snapshot.request().ruleVersions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queriesRequiredEventsWithMaximumWindowAndMapsExactReferences() {
        FinancialTransaction transaction = transfer(CUTOFF);
        RuleVersion r001 = publishedVersion(
                RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                15,
                amountCondition()
        );
        RuleVersion r002 = publishedVersion(
                RuleV1ContractRegistry
                        .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                20,
                deviceCondition()
        );
        BehaviorEvent event = new BehaviorEvent(
                UUID.randomUUID(),
                BehaviorEventType.DEVICE_REGISTERED,
                CUTOFF.minusSeconds(30),
                transaction.getExternalCustomerRef(),
                null,
                transaction.getDeviceRef(),
                null,
                null,
                "a".repeat(64)
        );
        when(ruleVersionRepository.findAllExecutableVersions(CUTOFF))
                .thenReturn(List.of(r002, r001));
        when(behaviorEventRepository.findForRuleEvaluation(
                eq(transaction.getExternalCustomerRef()),
                eq(Set.of(BehaviorEventType.DEVICE_REGISTERED)),
                eq(CUTOFF.minusSeconds(86_400)),
                eq(CUTOFF),
                any(Pageable.class)
        )).thenReturn(List.of(event));

        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                assembler.assemble(transaction);

        assertThat(snapshot.request().ruleVersions())
                .extracting(rule -> rule.ruleCode())
                .containsExactly(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        RuleV1ContractRegistry
                                .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT
                );
        assertThat(snapshot.request().behaviorEvents()).singleElement()
                .satisfies(value -> {
                    assertThat(value.eventType())
                            .isEqualTo(RuleBehaviorEventType.DEVICE_REGISTERED);
                    assertThat(value.deviceRef())
                            .isEqualTo(transaction.getDeviceRef());
                    assertThat(value.accountRef()).isNull();
                    assertThat(value.beneficiaryRef()).isNull();
                });
        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);
        verify(behaviorEventRepository).findForRuleEvaluation(
                eq(transaction.getExternalCustomerRef()),
                eq(Set.of(BehaviorEventType.DEVICE_REGISTERED)),
                eq(CUTOFF.minusSeconds(86_400)),
                eq(CUTOFF),
                pageable.capture()
        );
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1_000);
    }

    @Test
    void rejectsUnsupportedTransactionAndWindowBeforeReturningSnapshot() {
        FinancialTransaction unsupported = new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ATM_WITHDRAWAL,
                new BigDecimal("10000000.0000"),
                "KRW",
                CUTOFF,
                "customer_ref",
                "sender_ref",
                null,
                TransactionChannel.ATM,
                null
        );
        RuleVersion r004 = publishedVersion(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                10,
                beneficiaryCondition(86_400)
        );
        when(ruleVersionRepository.findAllExecutableVersions(CUTOFF))
                .thenReturn(List.of(r004));

        assertThatThrownBy(() -> assembler.assemble(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction type");

        RuleVersion unsupportedWindow = publishedVersion(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                10,
                beneficiaryCondition(60)
        );
        when(ruleVersionRepository.findAllExecutableVersions(CUTOFF))
                .thenReturn(List.of(unsupportedWindow));
        assertThatThrownBy(() -> assembler.assemble(transfer(CUTOFF)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");
        verify(behaviorEventRepository, never()).findForRuleEvaluation(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    void rejectsBehaviorWindowOverflow() {
        FinancialTransaction transaction = transfer(Instant.MIN);
        RuleVersion r004 = publishedVersion(
                RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER,
                10,
                beneficiaryCondition(86_400),
                Instant.MIN,
                null
        );
        when(ruleVersionRepository.findAllExecutableVersions(Instant.MIN))
                .thenReturn(List.of(r004));

        assertThatThrownBy(() -> assembler.assemble(transaction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        verify(behaviorEventRepository, never()).findForRuleEvaluation(
                any(), any(), any(), any(), any()
        );
    }

    private FinancialTransaction transfer(Instant occurredAt) {
        return new FinancialTransaction(
                UUID.randomUUID(),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000.0000"),
                "KRW",
                occurredAt,
                "customer_ref",
                "sender_ref",
                "recipient_ref",
                TransactionChannel.MOBILE_BANKING,
                "device_ref"
        );
    }

    private RuleVersion publishedVersion(
            String ruleCode,
            int weight,
            ObjectNode condition
    ) {
        return publishedVersion(
                ruleCode,
                weight,
                condition,
                CUTOFF.minusSeconds(120),
                null
        );
    }

    private RuleVersion publishedVersion(
            String ruleCode,
            int weight,
            ObjectNode condition,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        FraudRule rule = FraudRule.create(ruleCode, ruleCode, ruleCode);
        RuleVersion version = RuleVersion.draft(
                rule,
                1,
                ruleCode,
                weight,
                condition,
                effectiveFrom,
                effectiveTo
        );
        version.publish(effectiveFrom);
        return version;
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }

    private ObjectNode deviceCondition() {
        return objectMapper.createObjectNode()
                .put(
                        "prerequisiteRuleCode",
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT
                )
                .put("eventType", "DEVICE_REGISTERED")
                .put("windowSeconds", 86_400)
                .put("matchPolicy", "SAME_CUSTOMER_AND_DEVICE")
                .put(
                        "selectionPolicy",
                        "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                );
    }

    private ObjectNode beneficiaryCondition(int windowSeconds) {
        return objectMapper.createObjectNode()
                .put("eventType", "BENEFICIARY_REGISTERED")
                .put("windowSeconds", windowSeconds)
                .put(
                        "matchPolicy",
                        "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY"
                )
                .put(
                        "selectionPolicy",
                        "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                );
    }
}
