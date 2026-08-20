package com.aifds.backend.audit.repository;

import com.aifds.backend.audit.entity.AuditLog;

public interface AuditLogRepository {

    void insert(AuditLog auditLog);
}
