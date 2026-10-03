package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCasePageMetadataResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseTransactionListItemResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseTransactionListResponse;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryTimeoutException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryUnavailableException;
import com.aifds.backend.fraudcase.query.FraudCaseTransactionQuery;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.validation.FraudCaseTransactionQueryValidator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FraudCaseTransactionQueryService {
    private final FraudCaseTransactionQueryValidator validator;
    private final FraudCaseRepository cases;
    private final CaseTransactionRepository links;

    public FraudCaseTransactionQueryService(
            FraudCaseTransactionQueryValidator validator,
            FraudCaseRepository cases,
            CaseTransactionRepository links
    ) {
        this.validator = validator;
        this.cases = cases;
        this.links = links;
    }

    public FraudCaseTransactionListResponse findAll(
            FraudCaseTransactionQuery.Request request, String traceId
    ) {
        FraudCaseTransactionQuery query = validator.validate(request);
        Page<UUID> page;
        try {
            long casePk = cases.findByCaseId(query.caseId())
                    .orElseThrow(FraudCaseNotFoundException::new).getId();
            page = links.findTransactionIdsByFraudCasePk(
                    casePk, PageRequest.of(query.page(), query.size())
            );
        } catch (DataAccessException exception) {
            throw classify(exception);
        }
        return new FraudCaseTransactionListResponse(
                query.caseId(),
                page.getContent().stream().map(FraudCaseTransactionListItemResponse::new).toList(),
                new FraudCasePageMetadataResponse(
                        page.getNumber(), page.getSize(), page.getTotalElements(),
                        page.getTotalPages(), page.isFirst(), page.isLast()
                ),
                traceId
        );
    }

    private RuntimeException classify(DataAccessException exception) {
        if (hasCause(exception, QueryTimeoutException.class)) {
            return new FraudCaseQueryTimeoutException(exception);
        }
        if (hasCause(exception, TransientDataAccessResourceException.class)
                || hasCause(exception, DataAccessResourceFailureException.class)) {
            return new FraudCaseQueryUnavailableException(exception);
        }
        return exception;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = throwable; current != null && visited.add(current);
             current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
