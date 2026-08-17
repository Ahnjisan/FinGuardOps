package com.aifds.backend.rule.service;

import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.contract.RuleV1DefaultRuleSetDefinition;
import com.aifds.backend.rule.entity.FraudRule;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.repository.FraudRuleRepository;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleV1DefaultRuleSetPublicationServiceTest {

    private static final Instant CLOCK_INSTANT =
            Instant.parse("2026-08-17T05:00:00.123456Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final Instant EFFECTIVE_FROM =
            CLOCK_INSTANT.plusSeconds(1);
    private static final Instant STORED_PUBLISHED_AT =
            Instant.parse("2026-08-17T05:00:00Z");
    private static final String ALL_FOUR_HASH =
            "31299ea02656c1a5c72f2ead74b5ca468d087b4080249e5915d8882164d8121e";

    @Mock
    private RuleVersionRepository ruleVersionRepository;
    @Mock
    private FraudRuleRepository fraudRuleRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuleV1DefaultRuleSetPublicationService service;

    @BeforeEach
    void setUp() {
        service = new RuleV1DefaultRuleSetPublicationService(
                ruleVersionRepository,
                fraudRuleRepository,
                FIXED_CLOCK
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesExactlyFourVersionsInCanonicalLockAndMutationOrder() {
        Fixtures fixtures = draftFixtures();
        stubLocksInReverseOrder(fixtures);

        RuleV1DefaultRuleSetPublicationResult result = service.publish(
                EFFECTIVE_FROM
        );

        assertThat(result.outcome()).isEqualTo(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .PUBLISHED
        );
        assertThat(result.ruleVersionIds()).containsExactlyElementsOf(
                expectedRuleVersionIds()
        );
        assertThat(result.ruleSetVersion()).isEqualTo(ALL_FOUR_HASH);
        assertThat(result.publishedAt()).isEqualTo(CLOCK_INSTANT);
        assertThat(fixtures.versions())
                .extracting(RuleVersion::getStatus)
                .containsOnly(RuleVersionStatus.PUBLISHED);
        assertThat(fixtures.versions())
                .extracting(RuleVersion::getEffectiveFrom)
                .containsOnly(EFFECTIVE_FROM);
        assertThat(fixtures.versions())
                .extracting(RuleVersion::getPublishedAt)
                .containsOnly(CLOCK_INSTANT);

        InOrder lockOrder = inOrder(
                ruleVersionRepository,
                fraudRuleRepository
        );
        ArgumentCaptor<List<UUID>> versionIds = ArgumentCaptor.forClass(
                List.class
        );
        ArgumentCaptor<List<UUID>> ruleIds = ArgumentCaptor.forClass(
                List.class
        );
        lockOrder.verify(ruleVersionRepository)
                .findAllByRuleVersionIdInForUpdate(versionIds.capture());
        lockOrder.verify(fraudRuleRepository)
                .findAllByFraudRuleIdInForUpdate(ruleIds.capture());
        assertThat(versionIds.getValue()).containsExactlyElementsOf(
                expectedRuleVersionIds()
        );
        assertThat(ruleIds.getValue()).containsExactlyElementsOf(
                expectedFraudRuleIds()
        );
        ArgumentCaptor<Iterable<RuleVersion>> saved = ArgumentCaptor.forClass(
                Iterable.class
        );
        verify(ruleVersionRepository).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue())
                .extracting(version -> version.getFraudRule().getRuleCode())
                .containsExactly(
                        RuleV1ContractRegistry.TRANSFER_ABSOLUTE_HIGH_AMOUNT,
                        RuleV1ContractRegistry
                                .RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT,
                        RuleV1ContractRegistry
                                .RECENT_SECURITY_CHANGE_HIGH_AMOUNT,
                        RuleV1ContractRegistry.RECENT_BENEFICIARY_TRANSFER
                );
    }

    @Test
    void rejectsSetThatOmitsTheR001Dependency() {
        Fixtures missingDependency = draftFixtures();
        stubLocks(
                missingDependency.versions().subList(1, 4),
                missingDependency.rules().subList(1, 4)
        );

        assertRejectedWithoutSave(
                "complete V5",
                () -> service.publish(EFFECTIVE_FROM)
        );
    }

    @Test
    void rejectsMissingAndMismatchedIdentitiesBeforeMutation() {
        Fixtures missing = draftFixtures();
        stubLocks(
                missing.versions().subList(1, 4),
                missing.rules().subList(1, 4)
        );
        assertRejectedWithoutSave(
                "complete V5",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures wrongUuid = draftFixtures();
        ReflectionTestUtils.setField(
                wrongUuid.versions().get(0),
                "ruleVersionId",
                UUID.randomUUID()
        );
        stubLocks(wrongUuid.versions(), wrongUuid.rules());
        assertRejectedWithoutSave(
                "identity",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures wrongCode = draftFixtures();
        ReflectionTestUtils.setField(
                wrongCode.rules().get(0),
                "ruleCode",
                "WRONG_RULE_CODE"
        );
        stubLocks(wrongCode.versions(), wrongCode.rules());
        assertRejectedWithoutSave(
                "identity",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures wrongVersion = draftFixtures();
        ReflectionTestUtils.setField(
                wrongVersion.versions().get(0),
                "versionNumber",
                2
        );
        stubLocks(wrongVersion.versions(), wrongVersion.rules());
        assertRejectedWithoutSave(
                "identity",
                () -> service.publish(EFFECTIVE_FROM)
        );
    }

    @Test
    void rejectsReasonWeightAndConditionMismatchesBeforeMutation() {
        Fixtures wrongReason = draftFixtures();
        ReflectionTestUtils.setField(
                wrongReason.versions().get(0),
                "reasonCode",
                "WRONG_REASON"
        );
        stubLocks(wrongReason.versions(), wrongReason.rules());
        assertRejectedWithoutSave(
                "reasonCode",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures wrongWeight = draftFixtures();
        ReflectionTestUtils.setField(
                wrongWeight.versions().get(1),
                "weight",
                19
        );
        stubLocks(wrongWeight.versions(), wrongWeight.rules());
        assertRejectedWithoutSave(
                "weight",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures wrongCondition = draftFixtures();
        ReflectionTestUtils.setField(
                wrongCondition.versions().get(0),
                "conditionDefinition",
                amountCondition().put("amountThreshold", "20000000")
        );
        stubLocks(wrongCondition.versions(), wrongCondition.rules());
        assertRejectedWithoutSave(
                "amountThreshold",
                () -> service.publish(EFFECTIVE_FROM)
        );
    }

    @Test
    void rejectsMixedWithdrawnAndRetiredStates() {
        Fixtures mixed = draftFixtures();
        publish(mixed.versions().get(0), EFFECTIVE_FROM);
        stubLocks(mixed.versions(), mixed.rules());
        assertRejectedWithoutSave(
                "all DRAFT or all PUBLISHED",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures withdrawn = draftFixtures();
        withdrawn.versions().get(3).withdraw();
        stubLocks(withdrawn.versions(), withdrawn.rules());
        assertRejectedWithoutSave(
                "all DRAFT or all PUBLISHED",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures retired = draftFixtures();
        retired.rules().get(2).retire();
        stubLocks(retired.versions(), retired.rules());
        assertRejectedWithoutSave(
                "ACTIVE",
                () -> service.publish(EFFECTIVE_FROM)
        );
    }

    @Test
    void returnsIdempotentResultForExactPublishedSet() {
        Fixtures fixtures = draftFixtures();
        fixtures.versions().forEach(version -> publish(
                version,
                EFFECTIVE_FROM
        ));
        stubLocksInReverseOrder(fixtures);

        RuleV1DefaultRuleSetPublicationResult result = service.publish(
                EFFECTIVE_FROM
        );

        assertThat(result.outcome()).isEqualTo(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .ALREADY_PUBLISHED
        );
        assertThat(result.publishedAt()).isEqualTo(STORED_PUBLISHED_AT);
        assertThat(result.ruleSetVersion()).isEqualTo(ALL_FOUR_HASH);
        verify(ruleVersionRepository, never()).saveAllAndFlush(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsPublishedEffectiveFromAndMetadataMismatch() {
        Fixtures wrongEffectiveFrom = draftFixtures();
        wrongEffectiveFrom.versions().forEach(version -> publish(
                version,
                EFFECTIVE_FROM.plusSeconds(1)
        ));
        stubLocks(wrongEffectiveFrom.versions(), wrongEffectiveFrom.rules());
        assertRejectedWithoutSave(
                "effectiveFrom",
                () -> service.publish(EFFECTIVE_FROM)
        );

        Fixtures wrongMetadata = draftFixtures();
        wrongMetadata.versions().forEach(version -> publish(
                version,
                EFFECTIVE_FROM
        ));
        ReflectionTestUtils.setField(
                wrongMetadata.versions().get(3),
                "weight",
                9
        );
        stubLocks(wrongMetadata.versions(), wrongMetadata.rules());
        assertRejectedWithoutSave(
                "weight",
                () -> service.publish(EFFECTIVE_FROM)
        );
    }

    @Test
    void rejectsDraftPeriodMetadataAndNonMicrosecondEffectiveFrom() {
        Fixtures configuredDraft = draftFixtures();
        configuredDraft.versions().get(0).updateDraft(
                configuredDraft.versions().get(0).getReasonCode(),
                configuredDraft.versions().get(0).getWeight(),
                configuredDraft.versions().get(0).getConditionDefinition(),
                EFFECTIVE_FROM,
                null
        );
        stubLocks(configuredDraft.versions(), configuredDraft.rules());
        assertRejectedWithoutSave(
                "metadata must be unset",
                () -> service.publish(EFFECTIVE_FROM)
        );

        assertThatThrownBy(() -> service.publish(
                EFFECTIVE_FROM.plusNanos(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("microsecond");
    }

    @Test
    void acceptsOnlyEffectiveFromStrictlyAfterTheInjectedClock() {
        Fixtures atCurrentTime = draftFixtures();
        stubLocks(atCurrentTime.versions(), atCurrentTime.rules());
        assertRejectedWithoutSave(
                "later than the publication time",
                () -> service.publish(CLOCK_INSTANT)
        );

        Fixtures inThePast = draftFixtures();
        stubLocks(inThePast.versions(), inThePast.rules());
        assertRejectedWithoutSave(
                "later than the publication time",
                () -> service.publish(CLOCK_INSTANT.minusNanos(1_000))
        );

        Fixtures inTheFuture = draftFixtures();
        stubLocks(inTheFuture.versions(), inTheFuture.rules());

        RuleV1DefaultRuleSetPublicationResult result = service.publish(
                CLOCK_INSTANT.plusNanos(1_000)
        );

        assertThat(result.effectiveFrom()).isEqualTo(
                CLOCK_INSTANT.plusNanos(1_000)
        );
        assertThat(result.publishedAt()).isEqualTo(CLOCK_INSTANT);
        assertThat(inTheFuture.versions())
                .extracting(RuleVersion::getPublishedAt)
                .containsOnly(CLOCK_INSTANT);
    }

    @Test
    void normalizesPublishedAtFromInjectedClockToMicroseconds() {
        Instant clockInstant = Instant.parse(
                "2026-08-17T05:00:00.123456789Z"
        );
        RuleV1DefaultRuleSetPublicationService nanosecondClockService =
                new RuleV1DefaultRuleSetPublicationService(
                        ruleVersionRepository,
                        fraudRuleRepository,
                        Clock.fixed(clockInstant, ZoneOffset.UTC)
                );
        Fixtures fixtures = draftFixtures();
        stubLocks(fixtures.versions(), fixtures.rules());

        RuleV1DefaultRuleSetPublicationResult result =
                nanosecondClockService.publish(
                        Instant.parse("2026-08-17T05:00:00.123457Z")
                );

        Instant expectedPublishedAt = Instant.parse(
                "2026-08-17T05:00:00.123456Z"
        );
        assertThat(result.publishedAt()).isEqualTo(expectedPublishedAt);
        assertThat(fixtures.versions())
                .extracting(RuleVersion::getPublishedAt)
                .containsOnly(expectedPublishedAt);
    }

    private Fixtures draftFixtures() {
        List<FraudRule> rules = new ArrayList<>();
        List<RuleVersion> versions = new ArrayList<>();
        for (RuleV1DefaultRuleSetDefinition.DefaultRule definition
                : RuleV1DefaultRuleSetDefinition.rules()) {
            FraudRule rule = FraudRule.create(
                    definition.ruleCode(),
                    definition.ruleCode(),
                    definition.ruleCode()
            );
            ReflectionTestUtils.setField(rule, "id", (long) definition.canonicalOrder());
            ReflectionTestUtils.setField(
                    rule,
                    "fraudRuleId",
                    definition.fraudRuleId()
            );
            RuleVersion version = RuleVersion.draft(
                    rule,
                    definition.versionNumber(),
                    definition.ruleCode(),
                    expectedWeight(definition.canonicalOrder()),
                    condition(definition.canonicalOrder()),
                    null,
                    null
            );
            ReflectionTestUtils.setField(
                    version,
                    "id",
                    (long) definition.canonicalOrder()
            );
            ReflectionTestUtils.setField(
                    version,
                    "ruleVersionId",
                    definition.ruleVersionId()
            );
            rules.add(rule);
            versions.add(version);
        }
        return new Fixtures(rules, versions);
    }

    private void stubLocksInReverseOrder(Fixtures fixtures) {
        List<RuleVersion> versions = new ArrayList<>(fixtures.versions());
        List<FraudRule> rules = new ArrayList<>(fixtures.rules());
        Collections.reverse(versions);
        Collections.reverse(rules);
        stubLocks(versions, rules);
    }

    private void stubLocks(
            List<RuleVersion> versions,
            List<FraudRule> rules
    ) {
        when(ruleVersionRepository.findAllByRuleVersionIdInForUpdate(
                expectedRuleVersionIds()
        )).thenReturn(versions);
        when(fraudRuleRepository.findAllByFraudRuleIdInForUpdate(
                expectedFraudRuleIds()
        )).thenReturn(rules);
    }

    private void publish(RuleVersion version, Instant effectiveFrom) {
        version.updateDraft(
                version.getReasonCode(),
                version.getWeight(),
                version.getConditionDefinition(),
                effectiveFrom,
                null
        );
        version.publish(STORED_PUBLISHED_AT);
    }

    private void assertRejectedWithoutSave(
            String message,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(message);
        verify(ruleVersionRepository, never()).saveAllAndFlush(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private List<UUID> expectedRuleVersionIds() {
        return RuleV1DefaultRuleSetDefinition.rules().stream()
                .map(RuleV1DefaultRuleSetDefinition.DefaultRule::ruleVersionId)
                .toList();
    }

    private List<UUID> expectedFraudRuleIds() {
        return RuleV1DefaultRuleSetDefinition.rules().stream()
                .map(RuleV1DefaultRuleSetDefinition.DefaultRule::fraudRuleId)
                .toList();
    }

    private int expectedWeight(int canonicalOrder) {
        return switch (canonicalOrder) {
            case 1 -> 15;
            case 2 -> 20;
            case 3 -> 40;
            case 4 -> 10;
            default -> throw new IllegalArgumentException();
        };
    }

    private ObjectNode condition(int canonicalOrder) {
        return switch (canonicalOrder) {
            case 1 -> amountCondition();
            case 2 -> objectMapper.createObjectNode()
                    .put(
                            "prerequisiteRuleCode",
                            RuleV1ContractRegistry
                                    .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                    )
                    .put("eventType", "DEVICE_REGISTERED")
                    .put("windowSeconds", 86_400)
                    .put("matchPolicy", "SAME_CUSTOMER_AND_DEVICE")
                    .put(
                            "selectionPolicy",
                            "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                    );
            case 3 -> objectMapper.createObjectNode()
                    .put(
                            "prerequisiteRuleCode",
                            RuleV1ContractRegistry
                                    .TRANSFER_ABSOLUTE_HIGH_AMOUNT
                    )
                    .put("passwordEventType", "PASSWORD_CHANGED")
                    .put(
                            "transferLimitEventType",
                            "TRANSFER_LIMIT_CHANGED"
                    )
                    .put("windowSeconds", 86_400)
                    .put(
                            "matchPolicy",
                            "SAME_CUSTOMER_AND_SENDER_ACCOUNT"
                    )
                    .put(
                            "sequencePolicy",
                            "PASSWORD_CHANGED_AT_OR_BEFORE_"
                                    + "TRANSFER_LIMIT_CHANGED"
                    )
                    .put(
                            "selectionPolicy",
                            "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_"
                                    + "LATEST_PASSWORD_THEN_EVENT_ID_ASC"
                    );
            case 4 -> objectMapper.createObjectNode()
                    .put("eventType", "BENEFICIARY_REGISTERED")
                    .put("windowSeconds", 86_400)
                    .put(
                            "matchPolicy",
                            "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY"
                    )
                    .put(
                            "selectionPolicy",
                            "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                    );
            default -> throw new IllegalArgumentException();
        };
    }

    private ObjectNode amountCondition() {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.putArray("transactionTypes")
                .add("ACCOUNT_TRANSFER")
                .add("OPEN_BANKING_TRANSFER");
        return condition.put("currencyCode", "KRW")
                .put("amountThreshold", "10000000");
    }

    private record Fixtures(
            List<FraudRule> rules,
            List<RuleVersion> versions
    ) {
    }
}
