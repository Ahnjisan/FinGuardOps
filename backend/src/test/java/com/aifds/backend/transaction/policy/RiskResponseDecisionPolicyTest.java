package com.aifds.backend.transaction.policy;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class RiskResponseDecisionPolicyTest {

    private final RiskResponseDecisionPolicy policy =
            new RiskResponseDecisionPolicy();

    @Test
    void decidesLowAsApprovedWithoutCase() {
        RiskResponseDecision decision = policy.decide(RiskLevel.LOW);

        assertThat(decision.sourceRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(decision.targetTransactionStatus())
                .isEqualTo(TransactionProcessingStatus.APPROVED);
        assertThat(decision.riskResponseOutcome())
                .isEqualTo(RiskResponseOutcome.APPROVED);
        assertThat(decision.caseRequired()).isFalse();
    }

    @Test
    void decidesMediumAsApprovedWithMonitoringWithoutCase() {
        RiskResponseDecision decision = policy.decide(RiskLevel.MEDIUM);

        assertThat(decision.sourceRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(decision.targetTransactionStatus())
                .isEqualTo(TransactionProcessingStatus.APPROVED);
        assertThat(decision.riskResponseOutcome())
                .isEqualTo(RiskResponseOutcome.APPROVED_WITH_MONITORING);
        assertThat(decision.caseRequired()).isFalse();
    }

    @Test
    void decidesHighAsAdditionalAuthenticationRequiredWithCase() {
        RiskResponseDecision decision = policy.decide(RiskLevel.HIGH);

        assertThat(decision.sourceRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(decision.targetTransactionStatus())
                .isEqualTo(TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED);
        assertThat(decision.riskResponseOutcome())
                .isEqualTo(RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED);
        assertThat(decision.caseRequired()).isTrue();
    }

    @Test
    void decidesCriticalAsHeldWithCase() {
        RiskResponseDecision decision = policy.decide(RiskLevel.CRITICAL);

        assertThat(decision.sourceRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(decision.targetTransactionStatus())
                .isEqualTo(TransactionProcessingStatus.HELD);
        assertThat(decision.riskResponseOutcome())
                .isEqualTo(RiskResponseOutcome.HELD);
        assertThat(decision.caseRequired()).isTrue();
    }

    @Test
    void rejectsNullRiskLevel() {
        assertThatNullPointerException()
                .isThrownBy(() -> policy.decide(null))
                .withMessage("riskLevel must not be null");
    }

    @Test
    void returnsEqualDecisionsForRepeatedInput() {
        RiskResponseDecision first = policy.decide(RiskLevel.HIGH);
        RiskResponseDecision second = policy.decide(RiskLevel.HIGH);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void handlesEveryDeclaredRiskLevel() {
        EnumSet<RiskLevel> allRiskLevels = EnumSet.allOf(RiskLevel.class);

        assertThat(allRiskLevels).hasSize(4);
        allRiskLevels.forEach(riskLevel ->
                assertThatCode(() -> policy.decide(riskLevel))
                        .doesNotThrowAnyException()
        );
    }

    @Test
    void returnsOutcomeSupportingItsSourceRiskLevel() {
        EnumSet.allOf(RiskLevel.class).forEach(riskLevel -> {
            RiskResponseDecision decision = policy.decide(riskLevel);

            assertThat(decision.riskResponseOutcome().supports(
                    decision.sourceRiskLevel()
            )).isTrue();
        });
    }

    @Test
    void exposesOnlyTheApprovedPublicDecisionMethod()
            throws NoSuchMethodException {
        Method decide = RiskResponseDecisionPolicy.class.getMethod(
                "decide",
                RiskLevel.class
        );

        assertThat(decide.getReturnType())
                .isEqualTo(RiskResponseDecision.class);
        assertThat(Arrays.stream(
                RiskResponseDecisionPolicy.class.getDeclaredMethods()
        ).filter(method -> Modifier.isPublic(method.getModifiers())))
                .containsExactly(decide);
    }

    @Test
    void hasNoSpringAnnotationOrForbiddenStatefulDependency() {
        Field[] declaredFields =
                RiskResponseDecisionPolicy.class.getDeclaredFields();
        List<String> forbiddenTypeMarkers = List.of(
                ".entity.",
                ".repository.",
                ".externalrisk.",
                "java.time.Clock",
                "org.springframework.beans.",
                "org.springframework.context."
        );

        assertThat(Modifier.isPublic(
                RiskResponseDecisionPolicy.class.getModifiers()
        )).isTrue();
        assertThat(Modifier.isFinal(
                RiskResponseDecisionPolicy.class.getModifiers()
        )).isTrue();
        assertThat(RiskResponseDecisionPolicy.class.getAnnotations())
                .noneMatch(annotation -> annotation.annotationType()
                        .getPackageName().startsWith("org.springframework"));
        assertThat(Arrays.stream(declaredFields)
                .noneMatch(field -> forbiddenTypeMarkers.stream()
                        .anyMatch(marker ->
                                field.getType().getTypeName().contains(marker)
                                        || field.getGenericType()
                                        .getTypeName()
                                        .contains(marker)
                        )));
        assertThat(Arrays.stream(declaredFields)
                .filter(field -> Modifier.isStatic(field.getModifiers())))
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
        assertThat(Arrays.stream(declaredFields)
                .filter(field -> Modifier.isStatic(field.getModifiers())))
                .allMatch(field -> isKnownImmutableConstantType(
                        field.getType()
                ));
    }

    @Test
    void immutableConstantPredicateAllowsOnlyKnownImmutableTypes() {
        assertThat(List.of(
                int.class,
                String.class,
                Integer.class,
                BigDecimal.class,
                UUID.class,
                RiskLevel.class
        )).allMatch(RiskResponseDecisionPolicyTest
                ::isKnownImmutableConstantType);
        assertThat(List.of(
                String[].class,
                Collection.class,
                List.class,
                Set.class,
                Map.class,
                ArrayList.class,
                HashMap.class,
                StringBuilder.class,
                StringBuffer.class,
                Date.class,
                Calendar.class,
                AtomicReference.class,
                AtomicBoolean.class,
                AtomicInteger.class,
                AtomicLong.class,
                LongAdder.class,
                Clock.class
        )).noneMatch(RiskResponseDecisionPolicyTest
                ::isKnownImmutableConstantType);
    }

    @Test
    void decisionRecordHasExactlyTheApprovedComponents() {
        RecordComponent[] components =
                RiskResponseDecision.class.getRecordComponents();

        assertThat(components)
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "sourceRiskLevel",
                        "targetTransactionStatus",
                        "riskResponseOutcome",
                        "caseRequired"
                );
        assertThat(components)
                .extracting(RecordComponent::getType)
                .containsExactly(
                        RiskLevel.class,
                        TransactionProcessingStatus.class,
                        RiskResponseOutcome.class,
                        boolean.class
                );
    }

    @Test
    void decisionRecordRejectsNullObjectComponents() {
        assertThatNullPointerException().isThrownBy(() ->
                new RiskResponseDecision(
                        null,
                        TransactionProcessingStatus.APPROVED,
                        RiskResponseOutcome.APPROVED,
                        false
                )
        );
        assertThatNullPointerException().isThrownBy(() ->
                new RiskResponseDecision(
                        RiskLevel.LOW,
                        null,
                        RiskResponseOutcome.APPROVED,
                        false
                )
        );
        assertThatNullPointerException().isThrownBy(() ->
                new RiskResponseDecision(
                        RiskLevel.LOW,
                        TransactionProcessingStatus.APPROVED,
                        null,
                        false
                )
        );
    }

    private static boolean isKnownImmutableConstantType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || type == String.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == BigInteger.class
                || type == BigDecimal.class
                || type == UUID.class
                || type == Class.class;
    }
}
