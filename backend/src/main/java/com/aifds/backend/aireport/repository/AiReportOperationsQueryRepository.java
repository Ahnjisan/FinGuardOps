package com.aifds.backend.aireport.repository;

import com.aifds.backend.aireport.dto.AiReportOperationsDtos;
import com.aifds.backend.aireport.dto.AiReportUsageQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AiReportOperationsQueryRepository {
    private final JdbcTemplate jdbc;

    public AiReportOperationsQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Row> detail(UUID id) {
        return jdbc.query(BASE + " WHERE q.ai_request_id=?", this::row, id).stream().findFirst();
    }

    public List<Row> list(AiReportUsageQuery query) {
        Filter filter = filter(query);
        String order = query.sort().startsWith("aiRequestId") ? "q.ai_request_id" : "q.requested_at";
        String direction = query.sort().endsWith("asc") ? "ASC" : "DESC";
        List<Object> args = new ArrayList<>(filter.args());
        args.add(query.size());
        args.add((long) query.page() * query.size());
        return jdbc.query(BASE + " WHERE " + filter.sql() + " ORDER BY " + order + " " + direction
                + ", q.ai_request_id " + direction + " LIMIT ? OFFSET ?", this::row, args.toArray());
    }

    public long count(AiReportUsageQuery query) {
        Filter filter = filter(query);
        return jdbc.queryForObject("SELECT count(*) FROM ai_report_request q " + JOINS
                + " WHERE " + filter.sql(), Long.class, filter.args().toArray());
    }

    public Counts counts(AiReportUsageQuery query) {
        Filter filter = filter(query);
        return jdbc.queryForObject("""
                WITH selected AS (
                SELECT q.id, q.execution_id, q.status, q.cache_hit,
                       (q.execution_id IS NOT NULL AND e.status='FALLBACK_COMPLETED') AS fallback_used
                FROM ai_report_request q
                """ + JOINS + " WHERE " + filter.sql() + """
                ), executions AS (SELECT DISTINCT execution_id FROM selected WHERE execution_id IS NOT NULL),
                attempt_set AS (SELECT a.* FROM provider_call_attempt a
                    JOIN executions x ON x.execution_id=a.execution_id)
                SELECT (SELECT count(*) FROM selected),
                       (SELECT count(*) FROM executions),
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
                """, (rs, ignored) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                rs.getLong(9), rs.getObject(10, Long.class), rs.getLong(11),
                rs.getObject(12, Long.class)), filter.args().toArray());
    }

    public List<AiReportOperationsDtos.Attempt> attempts(long executionPk) {
        return jdbc.query("""
                SELECT attempt_number,provider,model_digest,input_tokens,output_tokens,
                       latency_ms,outcome,estimated_cost,cost_currency
                FROM provider_call_attempt WHERE execution_id=? ORDER BY attempt_number
                """, (rs, ignored) -> {
            Integer input = rs.getObject("input_tokens", Integer.class);
            Integer output = rs.getObject("output_tokens", Integer.class);
            return new AiReportOperationsDtos.Attempt(rs.getInt("attempt_number"),
                    rs.getString("provider"), rs.getString("model_digest"), rs.getString("outcome"),
                    input, output, input == null || output == null ? null : (long) input + output,
                    rs.getBigDecimal("estimated_cost") == null ? null
                            : rs.getBigDecimal("estimated_cost").toPlainString(),
                    rs.getString("cost_currency"), rs.getInt("latency_ms"));
        }, executionPk);
    }

    public UUID initiatingRequest(long executionPk) {
        return jdbc.queryForObject("""
                SELECT ai_request_id FROM ai_report_request WHERE execution_id=?
                AND cache_hit=false AND execution_shared=false
                ORDER BY requested_at DESC LIMIT 1
                """, UUID.class, executionPk);
    }

    public Long reportExecution(long reportPk) {
        return jdbc.queryForObject("SELECT execution_id FROM ai_report WHERE id=?", Long.class, reportPk);
    }

    private static final String JOINS = """
            JOIN fraud_case c ON c.id=q.fraud_case_id
            LEFT JOIN ai_report_execution e ON e.id=q.execution_id
            LEFT JOIN ai_report r ON r.id=q.report_id
            """;
    private static final String BASE = """
            SELECT q.id,q.ai_request_id,q.execution_id AS execution_pk,e.execution_id,
                   q.execution_shared,q.report_id AS report_pk,r.report_id,c.case_id,
                   q.detection_result_version,q.status,r.report_source,q.prompt_version,q.model_version,
                   q.cache_hit,q.requested_at,q.requested_by,q.trace_id,e.status AS execution_status,
                   e.failure_code,e.fallback_trigger_code,r.generated_at
            FROM ai_report_request q
            """ + JOINS;

    private Row row(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getLong("id"), rs.getObject("ai_request_id", UUID.class),
                rs.getObject("execution_pk", Long.class), rs.getObject("execution_id", UUID.class),
                rs.getBoolean("execution_shared"), rs.getObject("report_pk", Long.class),
                rs.getObject("report_id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getInt("detection_result_version"), rs.getString("status"),
                rs.getString("report_source"), rs.getString("prompt_version"),
                rs.getString("model_version"), rs.getBoolean("cache_hit"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("requested_by"),
                rs.getString("trace_id"), rs.getString("execution_status"),
                rs.getString("failure_code"), rs.getString("fallback_trigger_code"));
    }

    private Filter filter(AiReportUsageQuery q) {
        StringBuilder sql = new StringBuilder("q.requested_at >= ? AND q.requested_at < ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(q.from()), Timestamp.from(q.to())));
        if (q.reportStatus() != null) { sql.append(" AND q.status=?"); args.add(q.reportStatus()); }
        if (q.reportSource() != null) { sql.append(" AND r.report_source=?"); args.add(q.reportSource()); }
        if (q.cacheHit() != null) { sql.append(" AND q.cache_hit=?"); args.add(q.cacheHit()); }
        if (q.fallbackUsed() != null) {
            sql.append(" AND (q.execution_id IS NOT NULL AND e.status='FALLBACK_COMPLETED')=?");
            args.add(q.fallbackUsed());
        }
        if (q.provider() != null || q.model() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM provider_call_attempt match_attempt "
                    + "WHERE match_attempt.execution_id=q.execution_id");
            if (q.provider() != null) { sql.append(" AND match_attempt.provider=?"); args.add(q.provider()); }
            if (q.model() != null) { sql.append(" AND match_attempt.model_digest=?"); args.add(q.model()); }
            sql.append(")");
        }
        return new Filter(sql.toString(), args);
    }

    private record Filter(String sql, List<Object> args) { }
    public record Row(long pk, UUID aiRequestId, Long executionPk, UUID executionId,
                      boolean executionShared, Long reportPk, UUID reportId, UUID caseId,
                      int detectionResultVersion, String status, String reportSource,
                      String promptVersion, String modelVersion, boolean cacheHit,
                      Instant requestedAt, String requestedBy, String traceId,
                       String executionStatus, String failureCode,
                       String fallbackTriggerCode) { }
    public record Counts(long requests, long executions, long attempts, long successes,
                         long failures, long inProgress, long fallbacks, long cacheHits,
                         long missingInput, Long input, long missingOutput, Long output) { }
}
