package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCaseListRequest;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryTimeoutException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryUnavailableException;
import com.aifds.backend.fraudcase.repository.CaseTransactionRepository;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.validation.FraudCaseQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudCaseQueryServiceTest {

    private static final String CASE_ID =
            "10000000-0000-4000-9000-000000000001";
    private static final String TRACE_ID = "trace_case_service_01";

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private CaseTransactionRepository caseTransactionRepository;

    private FraudCaseQueryService service;

    @BeforeEach
    void setUp() {
        service = new FraudCaseQueryService(
                new FraudCaseQueryValidator(),
                fraudCaseRepository,
                caseTransactionRepository,
                new FraudCaseQueryMapper()
        );
    }

    @Test
    void usesStableDefaultPageAndOneBatchCountQuery() {
        FraudCase first = fraudCase(1L, CASE_ID);
        FraudCase second = fraudCase(
                2L,
                "10000000-0000-4000-9000-000000000002"
        );
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(first, second),
                PageRequest.of(0, 20),
                2
        ));
        CaseTransactionRepository.FraudCaseTransactionCount firstCount =
                count(1L, 2L);
        when(caseTransactionRepository.countByFraudCasePks(anyCollection()))
                .thenReturn(List.of(firstCount));

        var response = service.findAll(request(null), TRACE_ID);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).relatedTransactionCount())
                .isEqualTo(2L);
        assertThat(response.content().get(1).relatedTransactionCount())
                .isZero();
        assertThat(response.page().totalElements()).isEqualTo(2L);
        assertThat(response.traceId()).isEqualTo(TRACE_ID);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(
                Pageable.class
        );
        verify(fraudCaseRepository).findAll(
                any(Specification.class),
                pageable.capture()
        );
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().getOrderFor("lastChangedAt"))
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id"))
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
        verify(caseTransactionRepository).countByFraudCasePks(List.of(1L, 2L));
    }

    @Test
    void doesNotRunCountQueryForEmptyPage() {
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(),
                PageRequest.of(0, 20),
                0
        ));

        var response = service.findAll(request(null), TRACE_ID);

        assertThat(response.content()).isEmpty();
        assertThat(response.page().totalElements()).isZero();
        verifyNoInteractions(caseTransactionRepository);
    }

    @Test
    void appliesApprovedAscendingSortToBothKeys() {
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        service.findAll(request("lastChangedAt,asc"), TRACE_ID);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(
                Pageable.class
        );
        verify(fraudCaseRepository).findAll(
                any(Specification.class),
                pageable.capture()
        );
        assertThat(pageable.getValue().getSort())
                .extracting(Sort.Order::getDirection)
                .containsExactly(Sort.Direction.ASC, Sort.Direction.ASC);
    }

    @Test
    void returnsDetailWithBatchCountOrSafeNotFound() {
        FraudCase fraudCase = fraudCase(1L, CASE_ID);
        UUID id = UUID.fromString(CASE_ID);
        when(fraudCaseRepository.findByCaseId(id))
                .thenReturn(Optional.of(fraudCase));
        CaseTransactionRepository.FraudCaseTransactionCount detailCount =
                count(1L, 3L);
        when(caseTransactionRepository.countByFraudCasePks(List.of(1L)))
                .thenReturn(List.of(detailCount));

        var response = service.findByCaseId(CASE_ID, TRACE_ID);

        assertThat(response.fraudCase().caseId()).isEqualTo(id);
        assertThat(response.fraudCase().relatedTransactionCount()).isEqualTo(3L);
        assertThat(response.traceId()).isEqualTo(TRACE_ID);

        when(fraudCaseRepository.findByCaseId(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findByCaseId(CASE_ID, TRACE_ID))
                .isInstanceOf(FraudCaseNotFoundException.class)
                .hasMessage("Fraud case was not found")
                .hasMessageNotContaining(CASE_ID);
    }

    @Test
    void validatesBeforeRepositories() {
        assertThatThrownBy(() -> service.findAll(
                request("createdAt,desc"),
                TRACE_ID
        )).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(fraudCaseRepository, caseTransactionRepository);
    }

    @Test
    void classifiesOnlyWhitelistedDataAccessFailures() {
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new QueryTimeoutException("raw query"));
        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isInstanceOf(FraudCaseQueryTimeoutException.class)
                .hasMessage("Fraud case query timed out");

        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(new DataAccessResourceFailureException("raw db"));
        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isInstanceOf(FraudCaseQueryUnavailableException.class)
                .hasMessage("Fraud case query repository is unavailable");

        DataIntegrityViolationException unknown =
                new DataIntegrityViolationException("raw schema");
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(unknown);
        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isSameAs(unknown);
    }

    @Test
    void countRepositoryFailureUsesSameClassification() {
        FraudCase fraudCase = fraudCase(1L, CASE_ID);
        when(fraudCaseRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(fraudCase)));
        when(caseTransactionRepository.countByFraudCasePks(anyCollection()))
                .thenThrow(new QueryTimeoutException("count timeout"));

        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isInstanceOf(FraudCaseQueryTimeoutException.class);
    }

    @Test
    void serviceHasReadOnlyTransactionalBoundary() throws Exception {
        Transactional transactional = FraudCaseQueryService.class
                .getAnnotation(Transactional.class);
        Method list = FraudCaseQueryService.class.getMethod(
                "findAll",
                FraudCaseListRequest.class,
                String.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(list.getAnnotation(Transactional.class)).isNull();
    }

    private FraudCase fraudCase(long id, String caseId) {
        FraudCase fraudCase = FraudCase.open(
                UUID.fromString(caseId),
                Instant.parse("2026-08-01T00:00:00Z")
        );
        ReflectionTestUtils.setField(fraudCase, "id", id);
        return fraudCase;
    }

    private CaseTransactionRepository.FraudCaseTransactionCount count(
            long casePk,
            long transactionCount
    ) {
        CaseTransactionRepository.FraudCaseTransactionCount count =
                org.mockito.Mockito.mock(
                        CaseTransactionRepository.FraudCaseTransactionCount.class
                );
        when(count.getFraudCasePk()).thenReturn(casePk);
        when(count.getTransactionCount()).thenReturn(transactionCount);
        return count;
    }

    private FraudCaseListRequest request(String sort) {
        return new FraudCaseListRequest(
                null, null, null, null, null, null, null, null,
                null, null, sort
        );
    }
}
