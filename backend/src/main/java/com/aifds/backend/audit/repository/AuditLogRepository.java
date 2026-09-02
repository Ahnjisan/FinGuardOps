package com.aifds.backend.audit.repository;

import com.aifds.backend.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository {

    void insert(AuditLog auditLog);

    List<AuditLog> findTransactionFinalizationLogs(UUID transactionId);

    Page<AuditLog> findFraudCaseAuditLogs(
            UUID caseId,
            Pageable pageable
    );
}
