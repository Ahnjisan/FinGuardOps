package com.aifds.backend.audit.repository;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaAuditLogRepository implements AuditLogRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void insert(AuditLog auditLog) {
        entityManager.persist(Objects.requireNonNull(
                auditLog,
                "auditLog must not be null"
        ));
        entityManager.flush();
    }

    @Override
    public List<AuditLog> findTransactionFinalizationLogs(
            UUID transactionId
    ) {
        return entityManager.createQuery("""
                        SELECT audit
                        FROM AuditLog audit
                        WHERE audit.transactionId = :transactionId
                          AND audit.action IN :actions
                        ORDER BY audit.changedAt ASC, audit.id ASC
                        """, AuditLog.class)
                .setParameter(
                        "transactionId",
                        Objects.requireNonNull(
                                transactionId,
                                "transactionId must not be null"
                        )
                )
                .setParameter(
                        "actions",
                        List.of(
                                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                                AuditAction.TRANSACTION_STATUS_CHANGED
                        )
                )
                .getResultList();
    }
}
