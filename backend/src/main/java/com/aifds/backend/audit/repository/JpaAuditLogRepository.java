package com.aifds.backend.audit.repository;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditTargetType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    @Override
    public Page<AuditLog> findFraudCaseAuditLogs(
            UUID caseId,
            Pageable pageable
    ) {
        UUID targetId = Objects.requireNonNull(
                caseId,
                "caseId must not be null"
        );
        Pageable requestedPage = Objects.requireNonNull(
                pageable,
                "pageable must not be null"
        );
        Sort.Order changedAt = requestedPage.getSort().getOrderFor(
                "changedAt"
        );
        Sort.Order id = requestedPage.getSort().getOrderFor("id");
        if (changedAt == null || id == null
                || changedAt.getDirection() != id.getDirection()
                || requestedPage.getSort().stream().count() != 2) {
            throw new IllegalArgumentException(
                    "Audit log sort must use changedAt and id"
            );
        }

        long total = entityManager.createQuery("""
                        SELECT COUNT(audit)
                        FROM AuditLog audit
                        WHERE audit.targetType = :targetType
                          AND audit.targetId = :targetId
                        """, Long.class)
                .setParameter("targetType", AuditTargetType.FRAUD_CASE)
                .setParameter("targetId", targetId)
                .getSingleResult();
        if (requestedPage.getOffset() >= total) {
            return new PageImpl<>(List.of(), requestedPage, total);
        }

        String orderBy = changedAt.isAscending()
                ? "ORDER BY audit.changedAt ASC, audit.id ASC"
                : "ORDER BY audit.changedAt DESC, audit.id DESC";
        List<AuditLog> content = entityManager.createQuery("""
                        SELECT audit
                        FROM AuditLog audit
                        WHERE audit.targetType = :targetType
                          AND audit.targetId = :targetId
                        """ + orderBy, AuditLog.class)
                .setParameter("targetType", AuditTargetType.FRAUD_CASE)
                .setParameter("targetId", targetId)
                .setFirstResult(Math.toIntExact(requestedPage.getOffset()))
                .setMaxResults(requestedPage.getPageSize())
                .getResultList();
        return new PageImpl<>(content, requestedPage, total);
    }
}
