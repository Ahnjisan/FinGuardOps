package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.repository.AuditLogRepository;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListResponse;
import com.aifds.backend.fraudcase.dto.FraudCasePageMetadataResponse;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryTimeoutException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryUnavailableException;
import com.aifds.backend.fraudcase.query.FraudCaseAuditLogQuery;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.validation.FraudCaseAuditLogQueryValidator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class FraudCaseAuditLogService {

    private final FraudCaseAuditLogQueryValidator validator;
    private final FraudCaseRepository fraudCaseRepository;
    private final AuditLogRepository auditLogRepository;
    private final FraudCaseAuditLogMapper mapper;

    public FraudCaseAuditLogService(
            FraudCaseAuditLogQueryValidator validator,
            FraudCaseRepository fraudCaseRepository,
            AuditLogRepository auditLogRepository,
            FraudCaseAuditLogMapper mapper
    ) {
        this.validator = validator;
        this.fraudCaseRepository = fraudCaseRepository;
        this.auditLogRepository = auditLogRepository;
        this.mapper = mapper;
    }

    public FraudCaseAuditLogListResponse findAll(
            FraudCaseAuditLogQuery.Request request,
            String traceId
    ) {
        FraudCaseAuditLogQuery query = validator.validate(request);
        Page<AuditLog> page;
        try {
            if (!fraudCaseRepository.existsByCaseId(query.caseId())) {
                throw new FraudCaseNotFoundException();
            }
            Sort sort = Sort.by(
                    new Sort.Order(query.sortDirection(), "changedAt"),
                    new Sort.Order(query.sortDirection(), "id")
            );
            page = auditLogRepository.findFraudCaseAuditLogs(
                    query.caseId(),
                    PageRequest.of(query.page(), query.size(), sort)
            );
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }

        List<FraudCaseAuditLogListItemResponse> content = page.getContent()
                .stream()
                .map(auditLog -> mapper.toResponse(
                        auditLog,
                        query.caseId()
                ))
                .toList();
        FraudCasePageMetadataResponse pageMetadata =
                new FraudCasePageMetadataResponse(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages(),
                        page.isFirst(),
                        page.isLast()
                );
        return new FraudCaseAuditLogListResponse(
                query.caseId(), content, pageMetadata, traceId
        );
    }

    private RuntimeException classifyDataAccessException(
            DataAccessException exception
    ) {
        if (hasCause(exception, QueryTimeoutException.class)) {
            return new FraudCaseQueryTimeoutException(exception);
        }
        if (hasCause(exception, TransientDataAccessResourceException.class)
                || hasCause(
                exception,
                DataAccessResourceFailureException.class
        )) {
            return new FraudCaseQueryUnavailableException(exception);
        }
        return exception;
    }

    private boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> causeType
    ) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
