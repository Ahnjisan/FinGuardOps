package com.aifds.backend.audit.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditLogPersistenceServiceTest {

    private static final Instant FIXED_NOW =
            Instant.parse("2026-08-20T05:10:11.123456789Z");
    private static final Instant EXPECTED_NOW =
            Instant.parse("2026-08-20T05:10:11.123456Z");

    @Mock
    private AuditLogRepository repository;

    private final AuditMetadataPolicy metadataPolicy =
            new AuditMetadataPolicy();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CountingClock clock;
    private AuditLogPersistenceService service;

    @BeforeEach
    void setUp() {
        clock = new CountingClock(FIXED_NOW);
        service = new AuditLogPersistenceService(
                repository,
                metadataPolicy,
                clock
        );
    }

    @Test
    void appendsWithGeneratedUuidAndOneMicrosecondClockValue() {
        UUID transactionId = UUID.randomUUID();
        AuditLogDraft draft = transactionStatusDraft(transactionId);

        PersistedAuditLog result = service.append(draft);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(
                AuditLog.class
        );
        verify(repository).insert(captor.capture());
        AuditLog inserted = captor.getValue();
        assertThat(inserted.getAuditId().version()).isEqualTo(4);
        assertThat(inserted.getChangedAt()).isEqualTo(EXPECTED_NOW);
        assertThat(result.auditId()).isEqualTo(inserted.getAuditId());
        assertThat(result.action()).isEqualTo(draft.action());
        assertThat(result.targetId()).isEqualTo(transactionId);
        assertThat(result.changedAt()).isEqualTo(EXPECTED_NOW);
        assertThat(clock.invocations()).isEqualTo(1);
    }

    @Test
    void propagatesRepositoryFailureWithoutReturningSuccess() {
        AuditLogDraft draft = transactionStatusDraft(UUID.randomUUID());
        IllegalStateException failure = new IllegalStateException(
                "database failure"
        );
        doThrow(failure).when(repository).insert(
                org.mockito.ArgumentMatchers.any(AuditLog.class)
        );

        assertThatThrownBy(() -> service.append(draft)).isSameAs(failure);
        verify(repository, times(1)).insert(
                org.mockito.ArgumentMatchers.any(AuditLog.class)
        );
    }

    @Test
    void usesDefaultRequiredTransactionAndReturnsImmutableRecord()
            throws Exception {
        Method append = AuditLogPersistenceService.class.getMethod(
                "append",
                AuditLogDraft.class
        );
        Transactional transactional = append.getAnnotation(
                Transactional.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRED);
        assertThat(transactional.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(PersistedAuditLog.class.isRecord()).isTrue();
    }

    private AuditLogDraft transactionStatusDraft(UUID transactionId) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transactionId,
                transactionId,
                null,
                "trace_audit_service_01",
                objectMapper.createObjectNode()
                        .put("processingStatus", "ANALYZED"),
                objectMapper.createObjectNode()
                        .put("processingStatus", "HELD"),
                objectMapper.createObjectNode()
                        .put("sourceRiskLevel", "CRITICAL")
        );
    }

    private static final class CountingClock extends Clock {

        private final Instant instant;
        private int invocations;

        private CountingClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            invocations++;
            return instant;
        }

        private int invocations() {
            return invocations;
        }
    }
}
