package com.aifds.backend.persistence;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class PostgresqlIntegrationTestSupport {

    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRESQL.start();
    }

    @Autowired
    private JdbcTemplate cleanupJdbcTemplate;

    @DynamicPropertySource
    static void registerPostgresqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @AfterEach
    void cleanDatabase() {
        cleanupJdbcTemplate.execute("""
                TRUNCATE TABLE
                    detection_evidence,
                    detection_result,
                    behavior_event,
                    idempotency_record,
                    financial_transaction
                RESTART IDENTITY CASCADE
                """);
    }
}
