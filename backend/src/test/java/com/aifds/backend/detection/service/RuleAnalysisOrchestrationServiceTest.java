package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.rule.client.RuleAnalysisClientErrorCategory;
import com.aifds.backend.rule.client.RuleAnalysisClientException;
import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequest;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuleAnalysisOrchestrationServiceTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001"
    );
    private static final UUID RESULT_ID = UUID.fromString(
            "70000000-0000-4000-8000-000000000001"
    );
    private static final String TRACE_ID = "trace_rule_orchestrator_01";
    private static final Instant NOW = Instant.parse(
            "2026-08-14T01:00:00Z"
    );

    private RuleAnalysisPersistenceService persistenceService;
    private RuleAnalysisHttpClient httpClient;
    private RuleAnalysisResponseMapper responseMapper;
    private RuleAnalysisOrchestrationService service;
    private StartedRuleAnalysis started;
    private StartedRuleAnalysisExecution execution;
    private StartedRuleAnalysisV2Execution executionV2;
    private RuleAnalysisRequest request;
    private RuleAnalysisRequestV2 requestV2;
    private ExternalRiskSnapshot externalRiskSnapshot;
    private RuleAnalysisResponse response;

    @BeforeEach
    void setUp() {
        persistenceService = mock(RuleAnalysisPersistenceService.class);
        httpClient = mock(RuleAnalysisHttpClient.class);
        responseMapper = mock(RuleAnalysisResponseMapper.class);
        service = new RuleAnalysisOrchestrationService(
                persistenceService,
                httpClient,
                responseMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        started = new StartedRuleAnalysis(
                TRANSACTION_ID,
                RESULT_ID,
                2,
                "a".repeat(64),
                "scoring-policy-v1",
                "rule-v1",
                null,
                NOW.minusSeconds(30),
                TRACE_ID
        );
        execution = mock(StartedRuleAnalysisExecution.class);
        executionV2 = mock(StartedRuleAnalysisV2Execution.class);
        request = mock(RuleAnalysisRequest.class);
        requestV2 = mock(RuleAnalysisRequestV2.class);
        externalRiskSnapshot = mock(ExternalRiskSnapshot.class);
        response = mock(RuleAnalysisResponse.class);
        when(execution.startedAnalysis()).thenReturn(started);
        when(execution.request()).thenReturn(request);
        when(executionV2.startedAnalysis()).thenReturn(started);
        when(executionV2.request()).thenReturn(requestV2);
        when(persistenceService.startAnalysis(
                TRANSACTION_ID,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenReturn(execution);
        when(persistenceService.startAnalysisV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenReturn(executionV2);
        when(httpClient.analyze(request, TRACE_ID)).thenReturn(response);
        when(httpClient.analyzeV2(requestV2, TRACE_ID)).thenReturn(response);
        when(responseMapper.map(response)).thenReturn(mapped());
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void callsEachBoundaryInOrderAndReturnsCompletedAnalysis() {
        CompletedRuleAnalysis completed = service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        );

        assertThat(completed).isEqualTo(new CompletedRuleAnalysis(
                TRANSACTION_ID,
                RESULT_ID,
                2,
                55,
                RiskLevel.HIGH
        ));
        InOrder order = inOrder(
                persistenceService,
                httpClient,
                responseMapper
        );
        order.verify(persistenceService).startAnalysis(
                TRANSACTION_ID,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        );
        order.verify(httpClient).analyze(request, TRACE_ID);
        order.verify(responseMapper).map(response);
        order.verify(persistenceService).completeAndAdopt(
                started,
                55,
                RiskLevel.HIGH,
                NOW,
                List.of()
        );
        verify(httpClient, times(1)).analyze(request, TRACE_ID);
        verify(persistenceService, never()).failAnalysis(
                any(),
                any(),
                any()
        );
    }

    @Test
    void callsEachV2BoundaryInOrderWithoutUsingTheV1StartOrClient() {
        CompletedRuleAnalysis completed = service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        );

        assertThat(completed).isEqualTo(new CompletedRuleAnalysis(
                TRANSACTION_ID,
                RESULT_ID,
                2,
                55,
                RiskLevel.HIGH
        ));
        InOrder order = inOrder(
                persistenceService,
                httpClient,
                responseMapper
        );
        order.verify(persistenceService).startAnalysisV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        );
        order.verify(httpClient).analyzeV2(requestV2, TRACE_ID);
        order.verify(responseMapper).map(response);
        order.verify(persistenceService).completeAndAdopt(
                started,
                55,
                RiskLevel.HIGH,
                NOW,
                List.of()
        );
        verify(httpClient, times(1)).analyzeV2(requestV2, TRACE_ID);
        verify(httpClient, never()).analyze(any(), any());
        verify(persistenceService, never()).startAnalysis(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(persistenceService, never()).failAnalysis(
                any(),
                any(),
                any()
        );
    }

    @Test
    void startFailureDoesNotCallAnyLaterBoundary() {
        RuntimeException original = new IllegalStateException("start failed");
        when(persistenceService.startAnalysis(
                TRANSACTION_ID,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenThrow(original);

        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        verifyNoInteractions(httpClient, responseMapper);
        verify(persistenceService, never()).completeAndAdopt(
                any(),
                anyInt(),
                any(),
                any(),
                any()
        );
        verify(persistenceService, never()).failAnalysis(
                any(),
                any(),
                any()
        );
    }

    @Test
    void v2StartFailureDoesNotCallHttpCompletionOrFailureWriter() {
        RuntimeException original = new IllegalArgumentException(
                "v2 request mapping failed"
        );
        when(persistenceService.startAnalysisV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenThrow(original);

        Throwable thrown = catchThrowable(() -> service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        verify(httpClient, never()).analyzeV2(any(), any());
        verify(responseMapper, never()).map(any());
        verify(persistenceService, never()).completeAndAdopt(
                any(),
                anyInt(),
                any(),
                any(),
                any()
        );
        verify(persistenceService, never()).failAnalysis(
                any(),
                any(),
                any()
        );
    }

    @ParameterizedTest
    @EnumSource(RuleAnalysisClientErrorCategory.class)
    void storesEveryClientCategoryNameAndRethrowsTheSameException(
            RuleAnalysisClientErrorCategory category
    ) {
        RuleAnalysisClientException original = mock(
                RuleAnalysisClientException.class
        );
        when(original.category()).thenReturn(category);
        when(httpClient.analyze(request, TRACE_ID)).thenThrow(original);

        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        verify(persistenceService).failAnalysis(
                started,
                category.name(),
                NOW
        );
        verify(httpClient, times(1)).analyze(request, TRACE_ID);
        verify(responseMapper, never()).map(any());
        verify(persistenceService, never()).completeAndAdopt(
                any(),
                anyInt(),
                any(),
                any(),
                any()
        );
    }

    @ParameterizedTest
    @EnumSource(RuleAnalysisClientErrorCategory.class)
    void v2StoresEveryClientCategoryNameAndRethrowsTheSameException(
            RuleAnalysisClientErrorCategory category
    ) {
        RuleAnalysisClientException original = mock(
                RuleAnalysisClientException.class
        );
        when(original.category()).thenReturn(category);
        when(httpClient.analyzeV2(requestV2, TRACE_ID)).thenThrow(original);

        Throwable thrown = catchThrowable(() -> service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        verify(persistenceService).failAnalysis(
                started,
                category.name(),
                NOW
        );
        verify(httpClient, times(1)).analyzeV2(requestV2, TRACE_ID);
        verify(httpClient, never()).analyze(any(), any());
        verify(responseMapper, never()).map(any());
        verify(persistenceService, never()).completeAndAdopt(
                any(),
                anyInt(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void distinguishesUnexpectedHttpMappingAndAdoptionFailures() {
        assertFailureCodeAtHttp(
                new IllegalStateException("unexpected http"),
                RuleAnalysisOrchestrationService.HTTP_CALL_FAILED
        );

        resetSuccessfulStubs();
        RuntimeException mapping = new IllegalArgumentException("mapping");
        when(responseMapper.map(response)).thenThrow(mapping);
        assertSameFailure(
                mapping,
                RuleAnalysisOrchestrationService.RESPONSE_MAPPING_FAILED
        );

        resetSuccessfulStubs();
        RuntimeException adoption = new IllegalStateException("adoption");
        org.mockito.Mockito.doThrow(adoption)
                .when(persistenceService)
                .completeAndAdopt(
                        started,
                        55,
                        RiskLevel.HIGH,
                        NOW,
                        List.of()
                );
        assertSameFailure(
                adoption,
                RuleAnalysisOrchestrationService.ADOPTION_FAILED
        );
    }

    @Test
    void rejectsAnEntryTransactionBeforeStarting() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        verifyNoInteractions(persistenceService, httpClient, responseMapper);
    }

    @Test
    void rejectsAnEntryTransactionBeforeStartingV2() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Throwable thrown = catchThrowable(() -> service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        ));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        verifyNoInteractions(persistenceService, httpClient, responseMapper);
    }

    @Test
    void recordsBoundaryViolationDetectedAfterStart() {
        when(persistenceService.startAnalysis(
                TRANSACTION_ID,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenAnswer(invocation -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return execution;
        });

        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        verify(persistenceService).failAnalysis(
                started,
                RuleAnalysisOrchestrationService
                        .TRANSACTION_BOUNDARY_VIOLATION,
                NOW
        );
        verifyNoInteractions(httpClient, responseMapper);
    }

    @Test
    void preservesOriginalAndSuppressesARecordingFailure() {
        RuntimeException original = new IllegalStateException("http");
        RuntimeException recording = new IllegalStateException("recording");
        when(httpClient.analyze(request, TRACE_ID)).thenThrow(original);
        org.mockito.Mockito.doThrow(recording)
                .when(persistenceService)
                .failAnalysis(
                        started,
                        RuleAnalysisOrchestrationService.HTTP_CALL_FAILED,
                        NOW
                );

        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        assertThat(thrown.getSuppressed()).containsExactly(recording);
    }

    @Test
    void v2PreservesOriginalAndSuppressesARecordingFailure() {
        RuntimeException original = new IllegalStateException("v2 http");
        RuntimeException recording = new IllegalStateException("recording");
        when(httpClient.analyzeV2(requestV2, TRACE_ID)).thenThrow(original);
        org.mockito.Mockito.doThrow(recording)
                .when(persistenceService)
                .failAnalysis(
                        started,
                        RuleAnalysisOrchestrationService.HTTP_CALL_FAILED,
                        NOW
                );

        Throwable thrown = catchThrowable(() -> service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        assertThat(thrown.getSuppressed()).containsExactly(recording);
    }

    @Test
    void v2RecordsResponseMappingAndAdoptionFailures() {
        RuntimeException mapping = new IllegalArgumentException("v2 mapping");
        when(responseMapper.map(response)).thenThrow(mapping);

        Throwable mappingThrown = catchThrowable(() -> service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        ));

        assertThat(mappingThrown).isSameAs(mapping);
        verify(persistenceService).failAnalysis(
                started,
                RuleAnalysisOrchestrationService.RESPONSE_MAPPING_FAILED,
                NOW
        );

        org.mockito.Mockito.reset(
                persistenceService,
                httpClient,
                responseMapper
        );
        resetSuccessfulV2Stubs();
        RuntimeException adoption = new IllegalStateException("v2 adoption");
        org.mockito.Mockito.doThrow(adoption)
                .when(persistenceService)
                .completeAndAdopt(
                        started,
                        55,
                        RiskLevel.HIGH,
                        NOW,
                        List.of()
                );

        Throwable adoptionThrown = catchThrowable(() -> service.analyzeV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                TRACE_ID
        ));

        assertThat(adoptionThrown).isSameAs(adoption);
        verify(persistenceService).failAnalysis(
                started,
                RuleAnalysisOrchestrationService.ADOPTION_FAILED,
                NOW
        );
    }

    @Test
    void avoidsSelfSuppressionWhenRecordingThrowsTheOriginal() {
        RuntimeException original = new IllegalStateException("same");
        when(httpClient.analyze(request, TRACE_ID)).thenThrow(original);
        org.mockito.Mockito.doThrow(original)
                .when(persistenceService)
                .failAnalysis(
                        started,
                        RuleAnalysisOrchestrationService.HTTP_CALL_FAILED,
                        NOW
                );

        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    @Test
    void hasNoTransactionalAnnotationOnClassOrPublicMethod()
            throws NoSuchMethodException {
        Method analyze = RuleAnalysisOrchestrationService.class.getMethod(
                "analyze",
                UUID.class,
                String.class
        );
        Method analyzeV2 = RuleAnalysisOrchestrationService.class.getMethod(
                "analyzeV2",
                UUID.class,
                ExternalRiskSnapshot.class,
                String.class
        );

        assertThat(RuleAnalysisOrchestrationService.class
                .getAnnotation(Transactional.class)).isNull();
        assertThat(analyze.getAnnotation(Transactional.class)).isNull();
        assertThat(analyzeV2.getAnnotation(Transactional.class)).isNull();
    }

    private void assertFailureCodeAtHttp(
            RuntimeException original,
            String failureCode
    ) {
        when(httpClient.analyze(request, TRACE_ID)).thenThrow(original);
        assertSameFailure(original, failureCode);
    }

    private void assertSameFailure(
            RuntimeException original,
            String failureCode
    ) {
        Throwable thrown = catchThrowable(() -> service.analyze(
                TRANSACTION_ID,
                TRACE_ID
        ));

        assertThat(thrown).isSameAs(original);
        verify(persistenceService).failAnalysis(started, failureCode, NOW);
    }

    private void resetSuccessfulStubs() {
        org.mockito.Mockito.reset(
                persistenceService,
                httpClient,
                responseMapper
        );
        when(persistenceService.startAnalysis(
                TRANSACTION_ID,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenReturn(execution);
        when(httpClient.analyze(request, TRACE_ID)).thenReturn(response);
        when(responseMapper.map(response)).thenReturn(mapped());
    }

    private void resetSuccessfulV2Stubs() {
        when(executionV2.startedAnalysis()).thenReturn(started);
        when(executionV2.request()).thenReturn(requestV2);
        when(persistenceService.startAnalysisV2(
                TRANSACTION_ID,
                externalRiskSnapshot,
                "scoring-policy-v1",
                "rule-v1",
                null,
                TRACE_ID,
                NOW
        )).thenReturn(executionV2);
        when(httpClient.analyzeV2(requestV2, TRACE_ID)).thenReturn(response);
        when(responseMapper.map(response)).thenReturn(mapped());
    }

    private RuleAnalysisResponseMapper.MappedRuleAnalysisResult mapped() {
        return new RuleAnalysisResponseMapper.MappedRuleAnalysisResult(
                55,
                RiskLevel.HIGH,
                List.of()
        );
    }
}
