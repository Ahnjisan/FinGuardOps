package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.repository.TransactionIntakeMaintenanceGateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyMaintenanceSafetyVerifierTest {

    private TransactionIntakeMaintenanceGateRepository repository;
    private IdempotencyMaintenanceSafetyVerifier verifier;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionIntakeMaintenanceGateRepository.class);
        verifier = new IdempotencyMaintenanceSafetyVerifier(
                new TransactionIntakeMaintenanceGate(repository), repository);
    }

    @Test
    void requiresClosedGateAndZeroWebSessions() {
        when(repository.isOpenForShare()).thenReturn(true, false, false);
        when(repository.activeWebSessions()).thenReturn(1, 0);

        assertThat(verifier.isSafeForReconciliation()).isFalse();
        assertThat(verifier.isSafeForReconciliation()).isFalse();
        assertThat(verifier.isSafeForReconciliation()).isTrue();
    }

    @Test
    void closesAndReopensGate() {
        verifier.close();
        verifier.open();
        verify(repository).setClosed(true);
        verify(repository).setClosed(false);
    }

    @Test
    void refusesWhenSessionVisibilityCannotBeProven() {
        when(repository.isOpenForShare()).thenReturn(false);
        when(repository.activeWebSessions()).thenReturn(-1);
        assertThat(verifier.isSafeForReconciliation()).isFalse();
    }
}
