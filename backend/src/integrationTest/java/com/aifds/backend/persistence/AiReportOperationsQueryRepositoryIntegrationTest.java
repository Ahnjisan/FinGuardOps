package com.aifds.backend.persistence;

import com.aifds.backend.aireport.dto.AiReportUsageQuery;
import com.aifds.backend.aireport.repository.AiReportOperationsQueryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReportOperationsQueryRepositoryIntegrationTest {
    @Test
    void countsTwoSharedRequestsOnceAndPreservesUnknownTokens() {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            Timestamp at = Timestamp.from(Instant.parse("2026-10-01T12:00:00Z"));
            Long transaction = jdbc.queryForObject("""
                    INSERT INTO financial_transaction(transaction_id,transaction_type,amount,currency_code,
                    occurred_at,external_customer_ref,sender_account_ref,channel)
                    VALUES (?,'ATM_WITHDRAWAL',100000,'KRW',?,'synthetic-customer','synthetic-account','ATM')
                    RETURNING id
                    """, Long.class, UUID.randomUUID(), at);
            Long detection = jdbc.queryForObject("""
                    INSERT INTO detection_result(detection_result_id,financial_transaction_id,
                    detection_result_version,analysis_status,rule_set_version,scoring_policy_version,
                    feature_version,evaluation_cutoff_at,analysis_trace_id)
                    VALUES (?,?,1,'PENDING','rules-1','policy-1','features-1',?,'trace-test-001') RETURNING id
                    """, Long.class, UUID.randomUUID(), transaction, at);
            Long casePk = jdbc.queryForObject("""
                    INSERT INTO fraud_case(case_id,case_status,assignee_ref,review_started_at,
                    created_at,last_changed_at)
                    VALUES (?,'IN_REVIEW',?,?,?,?) RETURNING id
                    """, Long.class, UUID.randomUUID(), "synthetic-analyst", at, at, at);
            Long execution = jdbc.queryForObject("""
                    INSERT INTO ai_report_execution(execution_id,fraud_case_id,detection_result_id,
                    detection_result_version,prompt_version,model_version,status,fallback_trigger_code)
                    VALUES (?,?,?,1,'prompt-1','model-1','FALLBACK_COMPLETED','LLM_TIMEOUT') RETURNING id
                    """, Long.class, UUID.randomUUID(), casePk, detection);
            UUID initiatingId = UUID.randomUUID();
            for (int i = 0; i < 2; i++) {
                jdbc.update("""
                        INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,
                        idempotency_key,fingerprint,requested_by,detection_result_version,
                        prompt_version,model_version,status,cache_hit,execution_shared,trace_id,requested_at)
                        VALUES (?,?,?,?,?,'synthetic-analyst',1,'prompt-1','model-1',
                        'FALLBACK_COMPLETED',false,?,'trace-test-001',?)
                        """, i == 0 ? initiatingId : UUID.randomUUID(), casePk, execution,
                        "key-" + i, "a".repeat(64), i == 1, at);
            }
            UUID cacheId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai_report_request(ai_request_id,fraud_case_id,idempotency_key,
                    fingerprint,requested_by,detection_result_version,prompt_version,model_version,
                    status,cache_hit,execution_shared,trace_id,requested_at)
                    VALUES (?,?,'cache-key',?,'synthetic-analyst',1,'prompt-1','model-1',
                    'FALLBACK_COMPLETED',true,false,'trace-test-001',?)
                    """, cacheId, casePk, "b".repeat(64), at);
            for (int i = 1; i <= 2; i++) {
                jdbc.update("""
                        INSERT INTO provider_call_attempt(execution_id,attempt_number,provider,
                        model_digest,input_tokens,output_tokens,latency_ms,outcome)
                        VALUES (?,?,?,?,?,10,500,'TIMEOUT')
                        """, execution, i, i == 1 ? "OLLAMA_LOCAL" : "OTHER_PROVIDER",
                        i == 1 ? "digest-1" : "digest-2", i == 1 ? 100 : null);
            }
            var repo = new AiReportOperationsQueryRepository(jdbc);
            var query = AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                    "to", new String[]{"2026-10-02T00:00:00Z"}), true);
            var counts = repo.counts(query);
            assertEquals(3, counts.requests());
            assertEquals(1, counts.executions());
            assertEquals(2, counts.attempts());
            assertEquals(1, counts.missingInput());
            assertNull(repo.detail(UUID.randomUUID()).orElse(null));
            assertEquals("LLM_TIMEOUT", repo.detail(initiatingId).orElseThrow().fallbackTriggerCode());
            assertNull(repo.detail(cacheId).orElseThrow().fallbackTriggerCode());
            var filtered = AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                    "to", new String[]{"2026-10-02T00:00:00Z"},
                    "provider", new String[]{"OLLAMA_LOCAL"}), true);
            assertEquals(2, repo.count(filtered));
            var mismatched = AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                    "to", new String[]{"2026-10-02T00:00:00Z"},
                    "provider", new String[]{"OLLAMA_LOCAL"}, "model", new String[]{"digest-2"}), true);
            assertEquals(0, repo.count(mismatched));
            measureSyntheticPlans(jdbc, repo, casePk, detection, execution, at);
        }
    }

    private void measureSyntheticPlans(JdbcTemplate jdbc, AiReportOperationsQueryRepository repo,
                                       long casePk, long detection, long originalExecution, Timestamp at) {
        // Isolated PostgreSQL 17 fixture: 2,500 new executions, two requests and two attempts each.
        jdbc.update("""
                INSERT INTO ai_report_execution(execution_id,fraud_case_id,detection_result_id,
                detection_result_version,prompt_version,model_version,status)
                SELECT gen_random_uuid(),?,?,1,'prompt-1','model-1','COMPLETED'
                FROM generate_series(1,2500)
                """, casePk, detection);
        jdbc.update("""
                INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,
                idempotency_key,fingerprint,requested_by,detection_result_version,
                prompt_version,model_version,status,cache_hit,execution_shared,trace_id,requested_at)
                SELECT gen_random_uuid(),?,e.id,'bulk-'||e.id||'-'||n.n,repeat('c',64),
                       'synthetic-operator',1,'prompt-1','model-1','COMPLETED',false,
                       n.n=2,'trace-test-001',?::timestamptz + (e.id % 3600) * interval '1 second'
                FROM ai_report_execution e CROSS JOIN generate_series(1,2) n(n)
                WHERE e.fraud_case_id=? AND e.id<>?
                """, casePk, at, casePk, originalExecution);
        jdbc.update("""
                INSERT INTO provider_call_attempt(execution_id,attempt_number,provider,
                model_digest,input_tokens,output_tokens,latency_ms,outcome)
                SELECT e.id,n.n,CASE WHEN n.n=1 THEN 'OLLAMA_LOCAL' ELSE 'OTHER_PROVIDER' END,
                       CASE WHEN n.n=1 THEN 'digest-1' ELSE 'digest-2' END,
                       100,10,500,'COMPLETED'
                FROM ai_report_execution e CROSS JOIN generate_series(1,2) n(n)
                WHERE e.fraud_case_id=? AND e.id<>?
                """, casePk, originalExecution);
        jdbc.execute("ANALYZE ai_report_request");
        jdbc.execute("ANALYZE ai_report_execution");
        jdbc.execute("ANALYZE provider_call_attempt");

        var range = Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                "to", new String[]{"2026-10-02T00:00:00Z"});
        var page = AiReportUsageQuery.parse(Map.of("from", range.get("from"),
                "to", range.get("to"), "page", new String[]{"200"},
                "size", new String[]{"20"}), false);
        var summary = AiReportUsageQuery.parse(range, true);
        assertEquals(5003, repo.count(page));
        var deepRows = repo.list(page);
        assertEquals(20, deepRows.size());
        for (int i = 1; i < deepRows.size(); i++) {
            var previous = deepRows.get(i - 1);
            var current = deepRows.get(i);
            assertTrue(!previous.requestedAt().isBefore(current.requestedAt()));
        }
        assertEquals(deepRows.stream().map(AiReportOperationsQueryRepository.Row::aiRequestId).toList(),
                repo.list(page).stream().map(AiReportOperationsQueryRepository.Row::aiRequestId).toList());
        var matched = AiReportUsageQuery.parse(Map.of("from", range.get("from"),
                "to", range.get("to"), "provider", new String[]{"OLLAMA_LOCAL"},
                "model", new String[]{"digest-1"}), true);
        assertEquals(5002, repo.count(matched));
        var totals = repo.counts(summary);
        assertEquals(5003, totals.requests());
        assertEquals(2501, totals.executions());
        assertEquals(5002, totals.attempts());
        System.out.println("AI_USAGE_EXPLAIN fixture postgres="
                + jdbc.queryForObject("SHOW server_version", String.class)
                + " requests=5003 distinctExecutions=2501 attempts=5002"
                + " period=[2026-10-01T00:00:00Z,2026-10-02T00:00:00Z)"
                + " deepOffset=4000 pageSize=20 analyzed=true");

        // These statements mirror the repository's range, same-attempt EXISTS, and
        // distinct-execution SQL; the functional assertions above guard fixture meaning.
        String joins = """
                JOIN fraud_case c ON c.id=q.fraud_case_id
                LEFT JOIN ai_report_execution e ON e.id=q.execution_id
                LEFT JOIN ai_report r ON r.id=q.report_id
                """;
        String base = """
                SELECT q.id,q.ai_request_id,q.execution_id AS execution_pk,e.execution_id,
                       q.execution_shared,q.report_id AS report_pk,r.report_id,c.case_id,
                       q.detection_result_version,q.status,r.report_source,q.prompt_version,q.model_version,
                       q.cache_hit,q.requested_at,q.requested_by,q.trace_id,e.status AS execution_status,
                       e.failure_code,r.generated_at
                FROM ai_report_request q
                """ + joins;
        String period = "q.requested_at >= ? AND q.requested_at < ?";
        Timestamp from = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        Timestamp to = Timestamp.from(Instant.parse("2026-10-02T00:00:00Z"));
        String ordered = base + " WHERE " + period
                + " ORDER BY q.requested_at DESC, q.ai_request_id DESC LIMIT ? OFFSET ?";
        explain(jdbc, "period-list-first-page", ordered, from, to, 20, 0);
        explain(jdbc, "period-count", "SELECT count(*) FROM ai_report_request q " + joins
                + " WHERE " + period, from, to);
        explain(jdbc, "provider-model-exists-count", "SELECT count(*) FROM ai_report_request q "
                + joins + " WHERE " + period + " AND EXISTS (SELECT 1 FROM provider_call_attempt"
                + " match_attempt WHERE match_attempt.execution_id=q.execution_id"
                + " AND match_attempt.provider=? AND match_attempt.model_digest=?)",
                from, to, "OLLAMA_LOCAL", "digest-1");
        explain(jdbc, "distinct-execution-attempt-summary", """
                WITH selected AS (
                  SELECT q.id,q.execution_id,q.status,q.cache_hit,
                         (q.execution_id IS NOT NULL AND e.status='FALLBACK_COMPLETED') AS fallback_used
                  FROM ai_report_request q
                """ + joins + " WHERE " + period + """
                ), executions AS (SELECT DISTINCT execution_id FROM selected WHERE execution_id IS NOT NULL),
                attempt_set AS (SELECT a.* FROM provider_call_attempt a
                    JOIN executions x ON x.execution_id=a.execution_id)
                SELECT (SELECT count(*) FROM selected), (SELECT count(*) FROM executions),
                       (SELECT count(*) FROM attempt_set),
                       (SELECT count(*) FROM selected WHERE status IN ('COMPLETED','FALLBACK_COMPLETED')),
                       (SELECT count(*) FROM selected WHERE status='FAILED'),
                       (SELECT count(*) FROM selected WHERE status IN ('PENDING','GENERATING')),
                       (SELECT count(*) FROM selected WHERE fallback_used),
                       (SELECT count(*) FROM selected WHERE cache_hit),
                       (SELECT count(*) FROM attempt_set WHERE input_tokens IS NULL),
                       (SELECT sum(input_tokens) FROM attempt_set),
                       (SELECT count(*) FROM attempt_set WHERE output_tokens IS NULL),
                       (SELECT sum(output_tokens) FROM attempt_set)
                """, from, to);
        explain(jdbc, "period-list-page-200", ordered, from, to, 20, 4000);
    }

    private void explain(JdbcTemplate jdbc, String label, String sql, Object... args) {
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                String.class, args);
        assertTrue(plan.stream().anyMatch(line -> line.startsWith("Execution Time:")));
        System.out.println("AI_USAGE_EXPLAIN " + label + " BEGIN");
        plan.forEach(System.out::println);
        System.out.println("AI_USAGE_EXPLAIN " + label + " END");
    }
}
