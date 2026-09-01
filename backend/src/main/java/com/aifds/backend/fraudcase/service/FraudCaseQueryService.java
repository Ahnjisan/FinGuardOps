package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCaseDetailResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseListItemResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseListRequest;
import com.aifds.backend.fraudcase.dto.FraudCaseListResponse;
import com.aifds.backend.fraudcase.dto.FraudCasePageMetadataResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryTimeoutException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryUnavailableException;
import com.aifds.backend.fraudcase.query.FraudCaseQueryCriteria;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseSpecifications;
import com.aifds.backend.fraudcase.validation.FraudCaseQueryValidator;
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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FraudCaseQueryService {

    private final FraudCaseQueryValidator validator;
    private final FraudCaseRepository fraudCaseRepository;
    private final CaseTransactionRepository caseTransactionRepository;
    private final FraudCaseQueryMapper mapper;

    public FraudCaseQueryService(
            FraudCaseQueryValidator validator,
            FraudCaseRepository fraudCaseRepository,
            CaseTransactionRepository caseTransactionRepository,
            FraudCaseQueryMapper mapper
    ) {
        this.validator = validator;
        this.fraudCaseRepository = fraudCaseRepository;
        this.caseTransactionRepository = caseTransactionRepository;
        this.mapper = mapper;
    }

    public FraudCaseListResponse findAll(
            FraudCaseListRequest request,
            String traceId
    ) {
        FraudCaseQueryCriteria criteria = validator.validate(request);
        Page<FraudCase> page;
        Map<Long, Long> counts;
        try {
            page = fraudCaseRepository.findAll(
                    FraudCaseSpecifications.from(criteria),
                    pageable(criteria)
            );
            counts = transactionCounts(page.getContent());
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }

        List<FraudCaseListItemResponse> content = page.getContent().stream()
                .map(fraudCase -> mapper.toListItem(
                        fraudCase,
                        counts.getOrDefault(fraudCase.getId(), 0L)
                ))
                .toList();
        FraudCasePageMetadataResponse metadata =
                new FraudCasePageMetadataResponse(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages(),
                        page.isFirst(),
                        page.isLast()
                );
        return new FraudCaseListResponse(content, metadata, traceId);
    }

    public FraudCaseDetailResponse findByCaseId(
            String rawCaseId,
            String traceId
    ) {
        UUID caseId = validator.validateCaseId(rawCaseId);
        FraudCase fraudCase;
        Map<Long, Long> counts;
        try {
            fraudCase = fraudCaseRepository.findByCaseId(caseId)
                    .orElseThrow(FraudCaseNotFoundException::new);
            counts = transactionCounts(List.of(fraudCase));
        } catch (DataAccessException exception) {
            throw classifyDataAccessException(exception);
        }
        return new FraudCaseDetailResponse(
                mapper.toDetailItem(
                        fraudCase,
                        counts.getOrDefault(fraudCase.getId(), 0L)
                ),
                traceId
        );
    }

    private Pageable pageable(FraudCaseQueryCriteria criteria) {
        Sort.Direction direction = criteria.sortDirection();
        Sort sort = Sort.by(
                new Sort.Order(direction, "lastChangedAt"),
                new Sort.Order(direction, "id")
        );
        return PageRequest.of(criteria.page(), criteria.size(), sort);
    }

    private Map<Long, Long> transactionCounts(List<FraudCase> fraudCases) {
        if (fraudCases.isEmpty()) {
            return Map.of();
        }
        List<Long> fraudCasePks = fraudCases.stream()
                .map(FraudCase::getId)
                .toList();
        Map<Long, Long> counts = new HashMap<>();
        for (CaseTransactionRepository.FraudCaseTransactionCount count
                : caseTransactionRepository.countByFraudCasePks(
                fraudCasePks
        )) {
            Long previous = counts.put(
                    count.getFraudCasePk(),
                    count.getTransactionCount()
            );
            if (previous != null || count.getTransactionCount() < 0) {
                throw new IllegalStateException(
                        "Invalid fraud case transaction count result"
                );
            }
        }
        return Map.copyOf(counts);
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
