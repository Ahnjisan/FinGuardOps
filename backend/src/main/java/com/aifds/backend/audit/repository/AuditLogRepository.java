package com.aifds.backend.audit.repository;

import com.aifds.backend.audit.entity.AuditLog;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository {

    void insert(AuditLog auditLog);

    List<AuditLog> findTransactionFinalizationLogs(UUID transactionId);
}
