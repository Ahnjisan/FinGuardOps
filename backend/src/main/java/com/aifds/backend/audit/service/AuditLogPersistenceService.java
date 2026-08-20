package com.aifds.backend.audit.service;

import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditLogPersistenceService {

    private final AuditLogRepository repository;
    private final AuditMetadataPolicy metadataPolicy;
    private final Clock clock;

    public AuditLogPersistenceService(
            AuditLogRepository repository,
            AuditMetadataPolicy metadataPolicy,
            Clock clock
    ) {
        this.repository = repository;
        this.metadataPolicy = metadataPolicy;
        this.clock = clock;
    }

    @Transactional
    public PersistedAuditLog append(AuditLogDraft draft) {
        AuditLogDraft validated = Objects.requireNonNull(
                draft,
                "draft must not be null"
        );
        metadataPolicy.validate(validated);
        Instant changedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuditLog auditLog = AuditLog.create(
                UUID.randomUUID(),
                validated.actorType(),
                validated.actorId(),
                validated.action(),
                validated.reasonCode(),
                validated.targetType(),
                validated.targetId(),
                validated.transactionId(),
                validated.caseId(),
                validated.traceId(),
                validated.beforeValueSummary(),
                validated.afterValueSummary(),
                validated.metadata(),
                changedAt
        );
        repository.insert(auditLog);
        return new PersistedAuditLog(
                auditLog.getAuditId(),
                auditLog.getAction(),
                auditLog.getTargetType(),
                auditLog.getTargetId(),
                auditLog.getTransactionId(),
                auditLog.getCaseId(),
                auditLog.getChangedAt()
        );
    }
}
