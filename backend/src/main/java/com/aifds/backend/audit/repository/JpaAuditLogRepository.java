package com.aifds.backend.audit.repository;

import com.aifds.backend.audit.entity.AuditLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.Objects;

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
}
