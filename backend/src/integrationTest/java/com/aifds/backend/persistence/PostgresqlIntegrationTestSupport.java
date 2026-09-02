package com.aifds.backend.persistence;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
        registry.add(
                "finguardops.ai-service.base-url",
                () -> "http://127.0.0.1:65535"
        );
        registry.add(
                "finguardops.security.issuer",
                () -> "https://issuer.integration.test/finguardops"
        );
        registry.add(
                "finguardops.security.jwk-set-uri",
                () -> "https://issuer.integration.test/jwks"
        );
    }

    @AfterEach
    void cleanDatabase() {
        cleanupJdbcTemplate.execute("""
                TRUNCATE TABLE
                    idempotency_recovery_audit_log,
                    audit_log,
                    case_transaction,
                    fraud_case,
                    detection_evidence,
                    detection_result,
                    rule_version,
                    fraud_rule,
                    behavior_event,
                    idempotency_record,
                    financial_transaction
                RESTART IDENTITY CASCADE
                """);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/rule-v1-draft-seed.sql")
        );
        populator.execute(cleanupJdbcTemplate.getDataSource());
    }
}
