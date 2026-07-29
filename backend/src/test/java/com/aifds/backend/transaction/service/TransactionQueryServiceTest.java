package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.dto.TransactionListRequest;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.exception.TransactionQueryTimeoutException;
import com.aifds.backend.transaction.exception.TransactionQueryUnavailableException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.validation.TransactionQueryValidator;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    private static final String TRACE_ID = "trace_query_service_01";
    private static final String TRANSACTION_ID =
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";

    @Mock
    private FinancialTransactionRepository repository;

    private TransactionQueryService service;

    @BeforeEach
    void setUp() {
        service = new TransactionQueryService(
                new TransactionQueryValidator(),
                repository,
                new TransactionQueryMapper()
        );
    }

    @Test
    void appliesOccurredAtAndInternalIdAsSameDirectionStableSort() {
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        service.findAll(request("occurredAt,asc"), TRACE_ID);

        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                any(Specification.class),
                pageable.capture()
        );
        List<Sort.Order> orders = pageable.getValue().getSort().stream()
                .toList();
        assertThat(orders).containsExactly(
                new Sort.Order(Sort.Direction.ASC, "occurredAt"),
                new Sort.Order(Sort.Direction.ASC, "id")
        );
    }

    @Test
    void returnsExactPageMetadataAndCurrentTraceId() {
        Pageable returnedPageable = Pageable.ofSize(20);
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(),
                returnedPageable,
                0
        ));

        var response = service.findAll(request(null), TRACE_ID);

        assertThat(response.content()).isEmpty();
        assertThat(response.page().number()).isZero();
        assertThat(response.page().size()).isEqualTo(20);
        assertThat(response.page().totalElements()).isZero();
        assertThat(response.page().totalPages()).isZero();
        assertThat(response.page().first()).isTrue();
        assertThat(response.page().last()).isTrue();
        assertThat(response.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void validatesBeforeAccessingRepository() {
        TransactionListRequest invalid = new TransactionListRequest(
                null, null, null, null, null, null, "-1", null, null
        );

        assertThatThrownBy(() -> service.findAll(invalid, TRACE_ID))
                .isInstanceOf(RuntimeException.class);
        verify(repository, never()).findAll(
                any(Specification.class),
                any(Pageable.class)
        );
    }

    @Test
    void returnsDetailOrSafeNotFound() {
        UUID id = UUID.fromString(TRANSACTION_ID);
        FinancialTransaction transaction = org.mockito.Mockito.mock(
                FinancialTransaction.class
        );
        when(transaction.getTransactionId()).thenReturn(id);
        when(transaction.getAmount()).thenReturn(BigDecimal.ONE);
        when(repository.findByTransactionId(id))
                .thenReturn(Optional.of(transaction));

        var detail = service.findByTransactionId(TRANSACTION_ID, TRACE_ID);

        assertThat(detail.transaction().transactionId()).isEqualTo(id);
        assertThat(detail.traceId()).isEqualTo(TRACE_ID);

        when(repository.findByTransactionId(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                service.findByTransactionId(TRANSACTION_ID, TRACE_ID)
        ).isInstanceOf(TransactionNotFoundException.class)
                .hasMessage("Transaction was not found")
                .hasMessageNotContaining(TRANSACTION_ID);
    }

    @Test
    void classifiesOnlyWhitelistedDataAccessFailures() {
        QueryTimeoutException timeout =
                new QueryTimeoutException("raw query timeout details");
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(timeout);

        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isInstanceOf(TransactionQueryTimeoutException.class)
                .hasMessage("Transaction query timed out")
                .hasCause(timeout);

        DataAccessResourceFailureException unavailable =
                new DataAccessResourceFailureException(
                        "raw connection details"
                );
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(unavailable);

        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isInstanceOf(TransactionQueryUnavailableException.class)
                .hasMessage(
                        "Transaction query repository is unavailable"
                )
                .hasCause(unavailable);
    }

    @Test
    void leavesUnknownDataAccessAndRuntimeFailuresForSafe500Handler() {
        DataIntegrityViolationException unknownDataAccess =
                new DataIntegrityViolationException(
                        "raw table and column details"
                );
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(unknownDataAccess);

        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isSameAs(unknownDataAccess);

        RuntimeException unexpected =
                new RuntimeException("raw unexpected details");
        when(repository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenThrow(unexpected);

        assertThatThrownBy(() -> service.findAll(request(null), TRACE_ID))
                .isSameAs(unexpected);
    }

    private TransactionListRequest request(String sort) {
        return new TransactionListRequest(
                null, null, null, null, null, null, null, null, sort
        );
    }
}
