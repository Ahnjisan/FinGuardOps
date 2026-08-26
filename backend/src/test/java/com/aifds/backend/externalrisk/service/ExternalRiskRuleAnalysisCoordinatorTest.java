package com.aifds.backend.externalrisk.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.service.CompletedRuleAnalysis;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalRiskRuleAnalysisCoordinatorTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "53000000-0000-4000-8000-000000000001"
    );
    private static final String TRACE_ID = "trace-ext-risk-coordinator-0001";

    @Mock
    private ExternalRiskLookupCommandReader commandReader;

    @Mock
    private ExternalRiskPolicyService policyService;

    @Mock
    private RuleAnalysisOrchestrationService orchestrationService;

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void readsLooksUpAndAnalyzesV2ExactlyOnceInOrder() {
        ExternalRiskLookupCommand command = command();
        ExternalRiskSnapshot snapshot = snapshot();
        CompletedRuleAnalysis completed = completed();
        when(commandReader.read(TRANSACTION_ID, TRACE_ID)).thenReturn(command);
        when(policyService.lookup(command)).thenReturn(snapshot);
        when(orchestrationService.analyzeV2(
                TRANSACTION_ID,
                snapshot,
                TRACE_ID
        )).thenReturn(completed);

        CompletedRuleAnalysis actual = coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID);

        assertThat(actual).isSameAs(completed);
        InOrder order = inOrder(
                commandReader,
                policyService,
                orchestrationService
        );
        order.verify(commandReader).read(TRANSACTION_ID, TRACE_ID);
        order.verify(policyService).lookup(command);
        order.verify(orchestrationService).analyzeV2(
                TRANSACTION_ID,
                snapshot,
                TRACE_ID
        );
        verify(policyService, times(1)).lookup(command);
        verify(orchestrationService, times(1)).analyzeV2(
                TRANSACTION_ID,
                snapshot,
                TRACE_ID
        );
        verify(orchestrationService, never()).analyze(any(), anyString());
    }

    @Test
    void propagatesExternalRiskFailureWithoutCallingRuleAnalysis() {
        ExternalRiskLookupCommand command = command();
        ExternalRiskLookupException original =
                new ExternalRiskLookupException(
                        ExternalRiskFailureCategory.TIMEOUT
                );
        when(commandReader.read(TRANSACTION_ID, TRACE_ID)).thenReturn(command);
        when(policyService.lookup(command)).thenThrow(original);

        Throwable thrown = catchThrowable(() -> coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID));

        assertThat(thrown).isSameAs(original);
        verify(policyService, times(1)).lookup(command);
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void propagatesRuleFailureWithoutRetryOrFallback() {
        ExternalRiskLookupCommand command = command();
        ExternalRiskSnapshot snapshot = snapshot();
        RuntimeException original = new IllegalStateException(
                "rule analysis failed"
        );
        when(commandReader.read(TRANSACTION_ID, TRACE_ID)).thenReturn(command);
        when(policyService.lookup(command)).thenReturn(snapshot);
        when(orchestrationService.analyzeV2(
                TRANSACTION_ID,
                snapshot,
                TRACE_ID
        )).thenThrow(original);

        Throwable thrown = catchThrowable(() -> coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID));

        assertThat(thrown).isSameAs(original);
        verify(policyService, times(1)).lookup(command);
        verify(orchestrationService, times(1)).analyzeV2(
                TRANSACTION_ID,
                snapshot,
                TRACE_ID
        );
        verify(orchestrationService, never()).analyze(any(), anyString());
    }

    @Test
    void rejectsInvalidPublicInputsBeforeAnyDownstreamCall() {
        UUID nonV4 = UUID.fromString(
                "53000000-0000-1000-8000-000000000001"
        );

        for (Object[] input : new Object[][]{
                {null, TRACE_ID},
                {nonV4, TRACE_ID},
                {TRANSACTION_ID, null},
                {TRANSACTION_ID, "short"}
        }) {
            Throwable thrown = catchThrowable(() -> coordinator()
                    .analyzeWithExternalRisk(
                            (UUID) input[0],
                            (String) input[1]
                    ));
            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        }

        verifyNoInteractions(
                commandReader,
                policyService,
                orchestrationService
        );
    }

    @Test
    void rejectsAnActiveTransactionBeforeReadingOrCallingExternalRisk() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Throwable thrown = catchThrowable(() -> coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        verifyNoInteractions(
                commandReader,
                policyService,
                orchestrationService
        );
    }

    @Test
    void rejectsAReadBoundaryThatReturnsWithAnActiveTransaction() {
        when(commandReader.read(TRANSACTION_ID, TRACE_ID)).thenAnswer(
                invocation -> {
                    TransactionSynchronizationManager
                            .setActualTransactionActive(true);
                    return command();
                }
        );

        Throwable thrown = catchThrowable(() -> coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        verifyNoInteractions(policyService, orchestrationService);
    }

    @Test
    void rejectsAnActiveTransactionCreatedDuringPolicyLookupBeforeRuleCall() {
        ExternalRiskLookupCommand command = command();
        when(commandReader.read(TRANSACTION_ID, TRACE_ID)).thenReturn(command);
        when(policyService.lookup(command)).thenAnswer(invocation -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return snapshot();
        });

        Throwable thrown = catchThrowable(() -> coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        verify(policyService, times(1)).lookup(command);
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void rejectsNullSnapshotWithoutCallingRuleAnalysis() {
        ExternalRiskLookupCommand command = command();
        when(commandReader.read(TRANSACTION_ID, TRACE_ID)).thenReturn(command);
        when(policyService.lookup(command)).thenReturn(null);

        Throwable thrown = catchThrowable(() -> coordinator()
                .analyzeWithExternalRisk(TRANSACTION_ID, TRACE_ID));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("External Risk Policy returned no snapshot");
        verify(policyService, times(1)).lookup(command);
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void hasNoTransactionalOrServiceAnnotationAndIntroducesNoLogger() throws Exception {
        assertThat(ExternalRiskRuleAnalysisCoordinator.class
                .getAnnotation(Service.class)).isNull();
        assertThat(ExternalRiskRuleAnalysisCoordinator.class.getMethod(
                "analyzeWithExternalRisk",
                UUID.class,
                String.class
        ).getAnnotation(Transactional.class)).isNull();
        assertThat(Arrays.stream(
                ExternalRiskRuleAnalysisCoordinator.class.getDeclaredFields()
        ).map(Field::getType).map(Class::getName))
                .doesNotContain("org.slf4j.Logger");
    }

    private ExternalRiskRuleAnalysisCoordinator coordinator() {
        return new ExternalRiskRuleAnalysisCoordinator(
                commandReader,
                policyService,
                orchestrationService
        );
    }

    private ExternalRiskLookupCommand command() {
        return new ExternalRiskLookupCommand(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                Instant.parse("2026-08-26T01:00:00Z"),
                "customer-ref",
                "sender-ref",
                "recipient-ref",
                "device-ref",
                TRACE_ID
        );
    }

    private ExternalRiskSnapshot snapshot() {
        return new ExternalRiskSnapshot(
                TRANSACTION_ID,
                Instant.parse("2026-08-26T01:00:00Z"),
                Instant.parse("2026-08-26T01:00:01Z"),
                "EXTERNAL_RISK_MOCK_V1",
                Instant.parse("2026-08-26T00:59:59Z"),
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        );
    }

    private CompletedRuleAnalysis completed() {
        return new CompletedRuleAnalysis(
                TRANSACTION_ID,
                UUID.fromString("53000000-0000-4000-8000-000000000002"),
                1,
                15,
                RiskLevel.MEDIUM
        );
    }
}
