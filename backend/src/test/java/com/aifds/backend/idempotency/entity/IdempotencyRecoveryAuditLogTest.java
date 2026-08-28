package com.aifds.backend.idempotency.entity;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.idempotency.repository.IdempotencyRecoveryAuditLogRepository;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryDecision;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecoveryAuditLogTest {

    private static final Instant ATTEMPTED_AT =
            Instant.parse("2026-08-28T05:00:00.123456Z");

    @Test
    void createsSafeRecoveredAndRejectedAudits() {
        UUID transactionId = UUID.randomUUID();
        IdempotencyRecoveryAuditLog recovered = create(
                transactionId,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP,
                IdempotencyRecoveryAuditResult.RECOVERED
        );
        String userId = UUID.randomUUID().toString();
        IdempotencyRecoveryAuditLog rejected = create(
                null,
                AuditActorType.USER,
                userId,
                IdempotencyRecoveryDecision.MISSING_TRANSACTION,
                IdempotencyRecoveryAuditResult.REJECTED
        );

        assertThat(recovered.getAuditId().version()).isEqualTo(4);
        assertThat(recovered.getTransactionId()).isEqualTo(transactionId);
        assertThat(recovered.getAttemptedAt()).isEqualTo(ATTEMPTED_AT);
        assertThat(rejected.getActorId()).isEqualTo(userId);
        assertThat(rejected.getTransactionId()).isNull();
    }

    @Test
    void createsInternalFailureWithoutLowLevelDetails() {
        IdempotencyRecoveryAuditLog failed = create(
                null,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                IdempotencyRecoveryDecision.INTERNAL_FAILURE,
                IdempotencyRecoveryAuditResult.FAILED
        );

        assertThat(failed.getRecoveryDecision())
                .isEqualTo(IdempotencyRecoveryDecision.INTERNAL_FAILURE);
        assertThat(failed.getAuditResult())
                .isEqualTo(IdempotencyRecoveryAuditResult.FAILED);
        assertThat(Arrays.stream(
                IdempotencyRecoveryAuditLog.class.getDeclaredFields()
        ).map(field -> field.getName().toLowerCase()))
                .noneMatch(name -> name.contains("key"))
                .noneMatch(name -> name.contains("fingerprint"))
                .noneMatch(name -> name.contains("snapshot"))
                .noneMatch(name -> name.contains("exception"))
                .noneMatch(name -> name.contains("provider"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "another-system",
            "analyst@example.com",
            "not-a-uuid",
            "f47ac10b-58cc-11cf-a447-001122334455"
    })
    void rejectsInvalidActorContracts(String actorId) {
        AuditActorType actorType = "another-system".equals(actorId)
                ? AuditActorType.SYSTEM
                : AuditActorType.USER;
        assertThatThrownBy(() -> create(
                null,
                actorType,
                actorId,
                IdempotencyRecoveryDecision.MISSING_TRANSACTION,
                IdempotencyRecoveryAuditResult.REJECTED
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidDecisionResultAndTimestamp() {
        assertThatThrownBy(() -> create(
                null,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                IdempotencyRecoveryDecision.INTERNAL_FAILURE,
                IdempotencyRecoveryAuditResult.REJECTED
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");

        assertThatThrownBy(() -> IdempotencyRecoveryAuditLog.create(
                10L,
                null,
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                IdempotencyRecoveryDecision.MISSING_TRANSACTION,
                IdempotencyRecoveryAuditResult.REJECTED,
                Instant.parse("2026-08-28T05:00:00.123456789Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("microsecond");
    }

    @Test
    void isAppendOnlyAndExposesNoSensitiveOrMutationMethods() {
        assertThat(IdempotencyRecoveryAuditLog.class
                .isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(Arrays.stream(
                IdempotencyRecoveryAuditLog.class.getMethods()
        ).filter(method -> method.getDeclaringClass()
                == IdempotencyRecoveryAuditLog.class)
                .map(Method::getName))
                .noneMatch(name -> name.startsWith("set"))
                .noneMatch(name -> name.startsWith("delete"))
                .noneMatch(name -> name.startsWith("remove"));
        assertThat(Arrays.stream(
                IdempotencyRecoveryAuditLog.class.getDeclaredMethods()
        ).filter(method -> method.isAnnotationPresent(PreUpdate.class)))
                .hasSize(1)
                .allMatch(method -> Modifier.isPrivate(method.getModifiers()));
        assertThat(Arrays.stream(
                IdempotencyRecoveryAuditLog.class.getDeclaredMethods()
        ).filter(method -> method.isAnnotationPresent(PreRemove.class)))
                .hasSize(1)
                .allMatch(method -> Modifier.isPrivate(method.getModifiers()));
    }

    @Test
    void repositoryExposesOnlyNullSafeAppendOnlyInsert() {
        assertThat(IdempotencyRecoveryAuditLogRepository.class
                .isAnnotationPresent(
                        org.springframework.stereotype.Repository.class
                )).isTrue();
        assertThat(org.springframework.data.repository.Repository.class
                .isAssignableFrom(
                        IdempotencyRecoveryAuditLogRepository.class
                )).isFalse();
        assertThat(Arrays.stream(
                IdempotencyRecoveryAuditLogRepository.class
                        .getDeclaredMethods()
        ).filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("insert")
                .noneMatch(name -> name.startsWith("save"))
                .noneMatch(name -> name.startsWith("delete"));

        IdempotencyRecoveryAuditLogRepository repository =
                new IdempotencyRecoveryAuditLogRepository();
        assertThatThrownBy(() -> repository.insert(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("auditLog must not be null");
    }

    private IdempotencyRecoveryAuditLog create(
            UUID transactionId,
            AuditActorType actorType,
            String actorId,
            IdempotencyRecoveryDecision decision,
            IdempotencyRecoveryAuditResult result
    ) {
        return IdempotencyRecoveryAuditLog.create(
                10L,
                transactionId,
                actorType,
                actorId,
                decision,
                result,
                ATTEMPTED_AT
        );
    }
}
