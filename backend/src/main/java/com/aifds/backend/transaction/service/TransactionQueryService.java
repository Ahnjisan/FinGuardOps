package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.dto.PageMetadataResponse;
import com.aifds.backend.transaction.dto.TransactionDetailResponse;
import com.aifds.backend.transaction.dto.TransactionListItemResponse;
import com.aifds.backend.transaction.dto.TransactionListRequest;
import com.aifds.backend.transaction.dto.TransactionListResponse;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.exception.TransactionQueryTimeoutException;
import com.aifds.backend.transaction.exception.TransactionQueryUnavailableException;
import com.aifds.backend.transaction.query.TransactionQueryCriteria;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.repository.FinancialTransactionSpecifications;
import com.aifds.backend.transaction.validation.TransactionQueryValidator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private final TransactionQueryValidator validator;
    private final FinancialTransactionRepository repository;
    private final TransactionQueryMapper mapper;

    public TransactionQueryService(
            TransactionQueryValidator validator,
            FinancialTransactionRepository repository,
            TransactionQueryMapper mapper
    ) {
        this.validator = validator;
        this.repository = repository;
        this.mapper = mapper;
    }

    public TransactionListResponse findAll(
            TransactionListRequest request,
            String traceId
    ) {
        TransactionQueryCriteria criteria = validator.validate(request);
        Pageable pageable = pageable(criteria);

        Page<FinancialTransaction> page;
        try {
            page = repository.findAll(
                    FinancialTransactionSpecifications.from(criteria),
                    pageable
            );
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }

        List<TransactionListItemResponse> content = page.getContent().stream()
                .map(mapper::toListItem)
                .toList();
        PageMetadataResponse pageMetadata = new PageMetadataResponse(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
        return new TransactionListResponse(content, pageMetadata, traceId);
    }

    public TransactionDetailResponse findByTransactionId(
            String rawTransactionId,
            String traceId
    ) {
        UUID transactionId = validator.validateTransactionId(rawTransactionId);

        FinancialTransaction transaction;
        try {
            transaction = repository.findByTransactionId(transactionId)
                    .orElseThrow(TransactionNotFoundException::new);
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }
        return new TransactionDetailResponse(
                mapper.toDetailItem(transaction),
                traceId
        );
    }

    private Pageable pageable(TransactionQueryCriteria criteria) {
        Sort.Direction direction = criteria.sortDirection();
        Sort sort = Sort.by(
                new Sort.Order(direction, "occurredAt"),
                new Sort.Order(direction, "id")
        );
        return PageRequest.of(criteria.page(), criteria.size(), sort);
    }

    private RuntimeException classifyDataAccessException(
            DataAccessException exception
    ) {
        if (hasCause(exception, QueryTimeoutException.class)) {
            return new TransactionQueryTimeoutException(exception);
        }
        if (hasCause(exception, TransientDataAccessResourceException.class)
                || hasCause(
                exception,
                DataAccessResourceFailureException.class
        )) {
            return new TransactionQueryUnavailableException(exception);
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
