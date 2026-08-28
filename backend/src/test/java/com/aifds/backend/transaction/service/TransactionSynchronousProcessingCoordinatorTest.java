package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.service.ExternalRiskFailureSnapshotService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.observability.TransactionIntakeMetricsFilter;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.transaction.command.ValidatedTransactionCommand;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionSynchronousProcessingCoordinatorTest {

    private static final long RECORD_ID = 31L;
    private static final String TRACE_ID = "trace_sync_coordinator_01";
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final UUID RESULT_ID = UUID.fromString(
            "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101"
    );
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-27T00:00:00Z");

    @Mock private ObjectProvider<ExternalRiskRuleAnalysisCoordinator> provider;
    @Mock private ExternalRiskRuleAnalysisCoordinator analysisCoordinator;
    @Mock private TransactionIntakeWriter intakeWriter;
    @Mock private ExternalRiskFailureSnapshotService failureSnapshotService;
    @Mock private TransactionProcessingFailureStateReader failureStateReader;
    @Mock private IdempotencyService idempotencyService;
    @Mock private RiskResponseFinalizationService finalizationService;
    @Mock private TransactionIntakeCompletionService completionService;

    private TransactionSynchronousProcessingCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new TransactionSynchronousProcessingCoordinator(
                provider,
                intakeWriter,
                failureSnapshotService,
                failureStateReader,
                idempotencyService,
                finalizationService,
                completionService
        );
    }

    @Test
    void reportsUnavailableWithoutWritesOrDownstreamCalls() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(coordinator.isAvailable()).isFalse();
        assertThat(coordinator.process(RECORD_ID, command(), TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.ProviderUnavailable());
        verifyNoInteractions(
                intakeWriter,
                failureSnapshotService,
                failureStateReader,
                idempotencyService,
                finalizationService,
                completionService
        );
    }

    @Test
    void commitsReceivedThenAnalyzesFinalizesAndCompletesV2() {
        stubAvailableAndPersisted();
        RiskResponseFinalizationResult finalized = finalization(RiskLevel.LOW);
        TransactionIntakeResult.Received expected =
                new TransactionIntakeResult.Received(finalSnapshot(), 201);
        when(finalizationService.finalizeRiskResponse(TRANSACTION_ID))
                .thenReturn(finalized);
        when(completionService.complete(RECORD_ID, finalized, CREATED_AT))
                .thenReturn(expected);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return null;
        }).when(analysisCoordinator).analyzeWithExternalRiskSnapshot(
                TRANSACTION_ID,
                TRACE_ID,
                externalRiskSnapshot()
        );

        assertThat(coordinator.process(RECORD_ID, command(), TRACE_ID))
                .isSameAs(expected);

        InOrder order = inOrder(
                intakeWriter,
                analysisCoordinator,
                finalizationService,
                completionService
        );
        order.verify(intakeWriter).saveAndLink(RECORD_ID, command());
        order.verify(analysisCoordinator).lookupExternalRisk(
                TRANSACTION_ID,
                TRACE_ID
        );
        order.verify(analysisCoordinator).analyzeWithExternalRiskSnapshot(
                TRANSACTION_ID,
                TRACE_ID,
                externalRiskSnapshot()
        );
        order.verify(finalizationService).finalizeRiskResponse(TRANSACTION_ID);
        order.verify(completionService).complete(
                RECORD_ID,
                finalized,
                CREATED_AT
        );
    }

    @Test
    void typedExternalRiskFailurePersistsAndStopsRuleFinalization() {
        stubAvailableAndPersistedWithoutLookup();
        ExternalRiskLookupException original = new ExternalRiskLookupException(
                ExternalRiskFailureCategory.TIMEOUT
        );
        ExternalRiskFailureSnapshot snapshot = ExternalRiskFailureSnapshot.from(
                ExternalRiskFailureCategory.TIMEOUT,
                Instant.parse("2026-08-27T00:00:01Z")
        );
        when(analysisCoordinator.lookupExternalRisk(
                TRANSACTION_ID,
                TRACE_ID
        )).thenThrow(original);
        when(failureSnapshotService.persist(RECORD_ID, original))
                .thenReturn(snapshot);

        assertThat(coordinator.process(RECORD_ID, command(), TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.ExternalRiskFailure(
                        503,
                        "DEPENDENCY_TIMEOUT",
                        "탐지 서비스를 사용할 수 없습니다."
                ));
        verifyNoInteractions(failureStateReader, finalizationService,
                completionService);
    }

    @Test
    void externalRiskWriterFailureSuppressesOnAndRethrowsOriginal() {
        stubAvailableAndPersistedWithoutLookup();
        ExternalRiskLookupException original = new ExternalRiskLookupException(
                ExternalRiskFailureCategory.UNAVAILABLE
        );
        RuntimeException writerFailure = new IllegalStateException("writer");
        when(analysisCoordinator.lookupExternalRisk(
                TRANSACTION_ID,
                TRACE_ID
        )).thenThrow(original);
        when(failureSnapshotService.persist(RECORD_ID, original))
                .thenThrow(writerFailure);

        assertThatThrownBy(() -> coordinator.process(
                RECORD_ID,
                command(),
                TRACE_ID
        )).isSameAs(original)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(writerFailure));
    }

    @Test
    void untypedLookupFailureLeavesInProgressWithoutRuleFailureRead() {
        stubAvailableAndPersistedWithoutLookup();
        RuntimeException original = new IllegalStateException("command read");
        when(analysisCoordinator.lookupExternalRisk(
                TRANSACTION_ID,
                TRACE_ID
        )).thenThrow(original);

        assertThatThrownBy(() -> coordinator.process(
                RECORD_ID,
                command(),
                TRACE_ID
        )).isSameAs(original)
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).isNull();
                    assertThat(thrown.getSuppressed()).isEmpty();
                });
        verifyNoInteractions(
                failureSnapshotService,
                failureStateReader,
                idempotencyService,
                finalizationService,
                completionService
        );
        verify(analysisCoordinator, never()).analyzeWithExternalRiskSnapshot(
                any(),
                any(),
                any()
        );
    }

    @Test
    void confirmedRuleFailureWritesCodeOnlyFailure() {
        RuntimeException original = stubRuleFailure();
        when(failureStateReader.read(TRANSACTION_ID)).thenReturn(
                TransactionProcessingFailureStateReader.FailureState
                        .CONFIRMED_FAILURE
        );
        when(idempotencyService.fail(
                RECORD_ID,
                TransactionSynchronousProcessingCoordinator
                        .DEPENDENCY_UNAVAILABLE
        )).thenReturn(new IdempotencyClaimResult.Failed(
                TransactionSynchronousProcessingCoordinator
                        .DEPENDENCY_UNAVAILABLE
        ));

        assertThat(coordinator.process(RECORD_ID, command(), TRACE_ID))
                .isEqualTo(new TransactionIntakeResult.RuleFailure());
        verifyNoInteractions(finalizationService, completionService);
        assertThat(original.getSuppressed()).isEmpty();
    }

    @Test
    void indeterminateRuleFailureLeavesIdempotencyInProgress() {
        RuntimeException original = stubRuleFailure();
        when(failureStateReader.read(TRANSACTION_ID)).thenReturn(
                TransactionProcessingFailureStateReader.FailureState
                        .INDETERMINATE
        );

        assertThatThrownBy(() -> coordinator.process(
                RECORD_ID,
                command(),
                TRACE_ID
        )).isSameAs(original);
        verify(idempotencyService, never()).fail(anyLong(), any());
        verifyNoInteractions(finalizationService, completionService);
    }

    @Test
    void stateReaderAndFailureWriterErrorsAreSuppressedSeparately() {
        RuntimeException readerOriginal = stubRuleFailure();
        RuntimeException readerFailure = new IllegalStateException("reader");
        when(failureStateReader.read(TRANSACTION_ID)).thenThrow(readerFailure);
        assertThatThrownBy(() -> coordinator.process(
                RECORD_ID,
                command(),
                TRACE_ID
        )).isSameAs(readerOriginal)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(readerFailure));

        org.mockito.Mockito.reset(
                provider,
                analysisCoordinator,
                intakeWriter,
                failureStateReader,
                idempotencyService
        );
        RuntimeException writerOriginal = stubRuleFailure();
        RuntimeException writerFailure = new IllegalStateException("fail");
        when(failureStateReader.read(TRANSACTION_ID)).thenReturn(
                TransactionProcessingFailureStateReader.FailureState
                        .CONFIRMED_FAILURE
        );
        when(idempotencyService.fail(RECORD_ID, "DEPENDENCY_UNAVAILABLE"))
                .thenThrow(writerFailure);
        assertThatThrownBy(() -> coordinator.process(
                RECORD_ID,
                command(),
                TRACE_ID
        )).isSameAs(writerOriginal)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(writerFailure));
    }

    @Test
    void finalizationOrCompletionFailureDoesNotWriteAlternativeTerminalState() {
        stubAvailableAndPersisted();
        TransactionProcessingMetricsRecorder metricsRecorder = mock(
                TransactionProcessingMetricsRecorder.class
        );
        RuntimeException finalizationFailure =
                new IllegalStateException("finalization");
        when(finalizationService.finalizeRiskResponse(TRANSACTION_ID))
                .thenThrow(finalizationFailure);
        assertThatThrownBy(() -> inMetricsBoundary(
                () -> coordinator.process(RECORD_ID, command(), TRACE_ID),
                metricsRecorder
        )).isSameAs(finalizationFailure);
        verify(metricsRecorder).recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome
                        .FINALIZATION_FAILED
        );
        verifyNoInteractions(completionService);
        verify(idempotencyService, never()).fail(anyLong(), any());

        org.mockito.Mockito.reset(
                provider,
                analysisCoordinator,
                intakeWriter,
                finalizationService,
                completionService
        );
        org.mockito.Mockito.reset(metricsRecorder);
        stubAvailableAndPersisted();
        RiskResponseFinalizationResult finalized = finalization(RiskLevel.LOW);
        RuntimeException completionFailure =
                new IllegalStateException("completion");
        when(finalizationService.finalizeRiskResponse(TRANSACTION_ID))
                .thenReturn(finalized);
        when(completionService.complete(RECORD_ID, finalized, CREATED_AT))
                .thenThrow(completionFailure);
        assertThatThrownBy(() -> inMetricsBoundary(
                () -> coordinator.process(RECORD_ID, command(), TRACE_ID),
                metricsRecorder
        )).isSameAs(completionFailure);
        verify(metricsRecorder).recordIntakeOutcome(
                TransactionProcessingMetricsRecorder.IntakeOutcome
                        .COMPLETION_FAILED
        );
        verify(idempotencyService, never()).fail(anyLong(), any());
    }

    private void inMetricsBoundary(
            Runnable invocation,
            TransactionProcessingMetricsRecorder metricsRecorder
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/transactions"
        );
        request.setServletPath("/api/v1/transactions");
        ServletRequestAttributes attributes =
                new ServletRequestAttributes(request);
        try {
            new TransactionIntakeMetricsFilter(metricsRecorder).doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (servletRequest, servletResponse) -> {
                        RequestContextHolder.setRequestAttributes(attributes);
                        invocation.run();
                    }
            );
        } finally {
            RequestContextHolder.resetRequestAttributes();
            attributes.requestCompleted();
        }
    }

    @Test
    void coordinatorHasNoTransactionalBoundary() throws Exception {
        Method process = TransactionSynchronousProcessingCoordinator.class
                .getMethod(
                        "process",
                        long.class,
                        ValidatedTransactionCommand.class,
                        String.class
                );
        assertThat(TransactionSynchronousProcessingCoordinator.class
                .getAnnotation(Transactional.class)).isNull();
        assertThat(process.getAnnotation(Transactional.class)).isNull();
    }

    private RuntimeException stubRuleFailure() {
        stubAvailableAndPersisted();
        RuntimeException original = new IllegalStateException("rule");
        when(analysisCoordinator.analyzeWithExternalRiskSnapshot(
                TRANSACTION_ID,
                TRACE_ID,
                externalRiskSnapshot()
        )).thenThrow(original);
        return original;
    }

    private void stubAvailableAndPersisted() {
        stubAvailableAndPersistedWithoutLookup();
        when(analysisCoordinator.lookupExternalRisk(
                TRANSACTION_ID,
                TRACE_ID
        )).thenReturn(externalRiskSnapshot());
    }

    private void stubAvailableAndPersistedWithoutLookup() {
        when(provider.getIfAvailable()).thenReturn(analysisCoordinator);
        when(intakeWriter.saveAndLink(RECORD_ID, command())).thenReturn(
                new PersistedTransactionIntake(
                        TRANSACTION_ID,
                        TransactionProcessingStatus.RECEIVED,
                        CREATED_AT
                )
        );
    }

    private RiskResponseFinalizationResult finalization(RiskLevel riskLevel) {
        return new RiskResponseFinalizationResult(
                TRANSACTION_ID,
                RESULT_ID,
                riskLevel,
                TransactionProcessingStatus.APPROVED,
                RiskResponseOutcome.APPROVED,
                null,
                false
        );
    }

    private TransactionIntakeSnapshot finalSnapshot() {
        return new TransactionIntakeSnapshot(
                TRANSACTION_ID,
                TransactionProcessingStatus.APPROVED,
                "LOW",
                "APPROVED",
                RESULT_ID.toString(),
                null,
                CREATED_AT
        );
    }

    private ExternalRiskSnapshot externalRiskSnapshot() {
        return new ExternalRiskSnapshot(
                TRANSACTION_ID,
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                CREATED_AT,
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        );
    }

    private ValidatedTransactionCommand command() {
        return new ValidatedTransactionCommand(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1000"),
                "KRW",
                Instant.parse("2026-08-27T00:00:00Z"),
                "customer_ref_sync",
                "sender_ref_sync",
                "recipient_ref_sync",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_sync"
        );
    }
}
