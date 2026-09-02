package com.aifds.backend.fraudcase.service;

import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.repository.AuditLogRepository;
import com.aifds.backend.fraudcase.dto.FraudCaseAuditLogListItemResponse;
import com.aifds.backend.fraudcase.exception.FraudCaseConsistencyException;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryTimeoutException;
import com.aifds.backend.fraudcase.exception.FraudCaseQueryUnavailableException;
import com.aifds.backend.fraudcase.query.FraudCaseAuditLogQuery;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.validation.FraudCaseAuditLogQueryValidator;
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
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudCaseAuditLogServiceTest {

    private static final UUID CASE_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000001"
    );
    private static final String TRACE_ID = "trace_current_request_01";

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private FraudCaseAuditLogMapper mapper;

    private FraudCaseAuditLogService service;

    @BeforeEach
    void setUp() {
        service = new FraudCaseAuditLogService(
                new FraudCaseAuditLogQueryValidator(),
                fraudCaseRepository,
                auditLogRepository,
                mapper
        );
    }

    @Test
    void checksCaseBeforeQueryAndReturnsPageWithCurrentTrace() {
        AuditLog auditLog = org.mockito.Mockito.mock(AuditLog.class);
        FraudCaseAuditLogListItemResponse item =
                org.mockito.Mockito.mock(
                        FraudCaseAuditLogListItemResponse.class
                );
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(auditLog), PageRequest.of(0, 20), 1
        ));
        when(mapper.toResponse(auditLog, CASE_ID)).thenReturn(item);

        var response = service.findAll(request(Map.of()), TRACE_ID);

        assertThat(response.caseId()).isEqualTo(CASE_ID);
        assertThat(response.content()).containsExactly(item);
        assertThat(response.page().totalElements()).isEqualTo(1);
        assertThat(response.traceId()).isEqualTo(TRACE_ID);
        verify(fraudCaseRepository).existsByCaseId(CASE_ID);
        verify(auditLogRepository).findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        );
    }

    @Test
    void missingCaseNeverRunsAuditPageQuery() {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findAll(request(Map.of()), TRACE_ID))
                .isInstanceOf(FraudCaseNotFoundException.class);
        verify(auditLogRepository, never()).findFraudCaseAuditLogs(
                any(), any()
        );
    }

    @Test
    void returnsEmptyPageAndAppliesBothSortDirections() {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(), PageRequest.of(3, 5), 2
        ));

        var response = service.findAll(request(Map.of(
                "page", List.of("3"),
                "size", List.of("5"),
                "sort", List.of("changedAt,asc")
        )), TRACE_ID);
        assertThat(response.content()).isEmpty();
        assertThat(response.page().number()).isEqualTo(3);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(
                Pageable.class
        );
        verify(auditLogRepository).findFraudCaseAuditLogs(
                any(UUID.class), pageable.capture()
        );
        assertThat(pageable.getValue().getSort())
                .extracting(Sort.Order::getDirection)
                .containsExactly(Sort.Direction.ASC, Sort.Direction.ASC);
    }

    @Test
    void oneMappingFailureRejectsTheWholePage() {
        AuditLog first = org.mockito.Mockito.mock(AuditLog.class);
        AuditLog second = org.mockito.Mockito.mock(AuditLog.class);
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(first, second)));
        when(mapper.toResponse(first, CASE_ID)).thenReturn(
                org.mockito.Mockito.mock(
                        FraudCaseAuditLogListItemResponse.class
                )
        );
        when(mapper.toResponse(second, CASE_ID)).thenThrow(
                new FraudCaseConsistencyException("safe")
        );

        assertThatThrownBy(() -> service.findAll(request(Map.of()), TRACE_ID))
                .isInstanceOf(FraudCaseConsistencyException.class);
    }

    @Test
    void classifiesOnlyTimeoutAndUnavailableDatabaseFailures() {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenThrow(
                new QueryTimeoutException("raw timeout")
        );
        assertThatThrownBy(() -> service.findAll(request(Map.of()), TRACE_ID))
                .isInstanceOf(FraudCaseQueryTimeoutException.class);

        org.mockito.Mockito.reset(fraudCaseRepository);
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenThrow(
                new DataAccessResourceFailureException("raw unavailable")
        );
        assertThatThrownBy(() -> service.findAll(request(Map.of()), TRACE_ID))
                .isInstanceOf(FraudCaseQueryUnavailableException.class);

        org.mockito.Mockito.reset(fraudCaseRepository);
        DataIntegrityViolationException other =
                new DataIntegrityViolationException("raw schema");
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenThrow(other);
        assertThatThrownBy(() -> service.findAll(request(Map.of()), TRACE_ID))
                .isSameAs(other);
    }

    @Test
    void auditPageDatabaseFailureUsesTheSameClassification() {
        when(fraudCaseRepository.existsByCaseId(CASE_ID)).thenReturn(true);
        when(auditLogRepository.findFraudCaseAuditLogs(
                any(UUID.class), any(Pageable.class)
        )).thenThrow(new QueryTimeoutException("raw timeout"));

        assertThatThrownBy(() -> service.findAll(request(Map.of()), TRACE_ID))
                .isInstanceOf(FraudCaseQueryTimeoutException.class);
    }

    @Test
    void hasReadOnlyServiceTransactionBoundary() throws Exception {
        Transactional transaction = FraudCaseAuditLogService.class
                .getAnnotation(Transactional.class);
        Method method = FraudCaseAuditLogService.class.getMethod(
                "findAll",
                FraudCaseAuditLogQuery.Request.class,
                String.class
        );
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    private FraudCaseAuditLogQuery.Request request(
            Map<String, List<String>> parameters
    ) {
        return new FraudCaseAuditLogQuery.Request(
                CASE_ID.toString(), parameters
        );
    }
}
