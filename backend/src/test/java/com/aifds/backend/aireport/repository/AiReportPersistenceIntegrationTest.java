package com.aifds.backend.aireport.repository;

import com.aifds.backend.BackendApplication;
import com.aifds.backend.aireport.service.AiReportWorker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.aifds.backend.aireport.entity.AiReportStatus;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiReportPersistenceIntegrationTest {
    @Test
    void realPostgresqlMigratesAndRejectsSecondActiveExactExecution() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID transactionId = UUID.randomUUID();
            UUID resultId = UUID.randomUUID();
            UUID caseId = UUID.randomUUID();
            Instant now = Instant.now();
            Timestamp timestamp = Timestamp.from(now);
            Long transactionPk = jdbc.queryForObject("""
                    INSERT INTO financial_transaction(transaction_id,transaction_type,amount,currency_code,
                    occurred_at,external_customer_ref,sender_account_ref,channel)
                    VALUES (?,'ATM_WITHDRAWAL',100000,'KRW',?,'synthetic-customer','synthetic-account','ATM')
                    RETURNING id
                    """, Long.class, transactionId, timestamp);
            Long resultPk = jdbc.queryForObject("""
                    INSERT INTO detection_result(detection_result_id,financial_transaction_id,
                    detection_result_version,analysis_status,rule_set_version,scoring_policy_version,
                    feature_version,evaluation_cutoff_at,analysis_trace_id)
                    VALUES (?,?,1,'PENDING','rules-1','policy-1','features-1',?,'trace-test-001')
                    RETURNING id
                    """, Long.class, resultId, transactionPk, timestamp);
            Long casePk = jdbc.queryForObject("""
                    INSERT INTO fraud_case(case_id,case_status,assignee_ref,review_started_at,
                    created_at,last_changed_at)
                    VALUES (?,'IN_REVIEW',?,?,?,?) RETURNING id
                    """, Long.class, caseId, UUID.randomUUID().toString(), timestamp, timestamp, timestamp);
            var repository = new AiReportExecutionRepository(jdbc);
            var first = repository.insert(casePk, resultPk, 1, "prompt-1", "local-opaque-1");
            var requests = new AiReportRequestRepository(jdbc);
            requests.insert(UUID.randomUUID(), casePk, first.id(), null, "fixed-idempotency-key",
                    "a".repeat(64), "synthetic-analyst", 1, "prompt-1", "local-opaque-1",
                    AiReportStatus.PENDING, false, false, "trace-test-001");
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> requests.insert(UUID.randomUUID(), casePk, first.id(), null,
                            "fixed-idempotency-key", "a".repeat(64), "synthetic-analyst", 1,
                            "prompt-1", "local-opaque-1", AiReportStatus.PENDING, false,
                            true, "trace-test-002"));
            assertEquals(1, requests.initiators(first.id()).size());
            assertEquals(first.executionId(), repository.active(casePk, 1, "prompt-1", "local-opaque-1")
                    .orElseThrow().executionId());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> repository.insert(casePk, resultPk, 1, "prompt-1", "local-opaque-1"));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var claimed = transactions.execute(ignored -> repository.claim(300)).orElseThrow();
            assertEquals(first.executionId(), claimed.executionId());
            transactions.executeWithoutResult(ignored -> {
                assertEquals(true, repository.stillGenerating(first.id()));
                requests.generating(first.id());
                repository.complete(first.id(), AiReportStatus.FAILED, "PROVIDER_ERROR");
                requests.fail(first.id());
            });
            assertEquals(false, repository.stillGenerating(first.id()));
            assertEquals(AiReportStatus.FAILED,
                    requests.byKey(casePk, "fixed-idempotency-key").orElseThrow().status());
            assertEquals(true, repository.active(casePk, 1, "prompt-1", "local-opaque-1").isEmpty());
            try (var context = new SpringApplicationBuilder(BackendApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--SPRING_DATASOURCE_URL=" + postgres.getJdbcUrl(),
                            "--SPRING_DATASOURCE_USERNAME=" + postgres.getUsername(),
                            "--SPRING_DATASOURCE_PASSWORD=" + postgres.getPassword(),
                            "--finguardops.ai-service.base-url=http://localhost:8000",
                            "--finguardops.ai-report.poll-interval-ms=60000")) {
                assertEquals(1, context.getBeansOfType(AiReportWorker.class).size());
            }
        }
    }
}
