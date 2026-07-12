package com.aifds.backend.health.service;

import com.aifds.backend.health.dto.HealthResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthServiceTest {

    private final HealthService healthService = new HealthService();

    @Test
    void returnsBackendHealth() {
        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.service()).isEqualTo("backend");
    }
}
