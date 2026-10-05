package com.aifds.backend.aireport.repository;

import com.aifds.backend.aireport.entity.AiReportRequest;
import com.aifds.backend.aireport.entity.AiReportStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AiReportRequestRepository {
    private final JdbcTemplate jdbc;

    public AiReportRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AiReportRequest> byKey(long casePk, String key) {
        return jdbc.query("SELECT * FROM ai_report_request WHERE fraud_case_id=? AND idempotency_key=?",
                this::map, casePk, key).stream().findFirst();
    }

    public Optional<AiReportRequest> latest(long casePk) {
        return jdbc.query("SELECT * FROM ai_report_request WHERE fraud_case_id=? ORDER BY requested_at DESC, ai_request_id DESC LIMIT 1",
                this::map, casePk).stream().findFirst();
    }

    public List<AiReportRequest> initiators(long executionPk) {
        return jdbc.query("SELECT * FROM ai_report_request WHERE execution_id=? AND cache_hit=false AND execution_shared=false ORDER BY requested_at DESC LIMIT 1",
                this::map, executionPk);
    }

    public AiReportRequest insert(UUID id, long casePk, Long executionPk, Long reportPk,
                                  String key, String fingerprint, String requester,
                                  int version, String prompt, String model,
                                  AiReportStatus status, boolean cacheHit, boolean shared,
                                  String traceId) {
        jdbc.update("""
                INSERT INTO ai_report_request(ai_request_id,fraud_case_id,execution_id,report_id,
                idempotency_key,fingerprint,requested_by,detection_result_version,prompt_version,
                model_version,status,cache_hit,execution_shared,trace_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, casePk, executionPk, reportPk, key, fingerprint, requester,
                version, prompt, model, status.name(), cacheHit, shared, traceId);
        return byKey(casePk, key).orElseThrow();
    }

    public void complete(long executionPk, long reportPk, AiReportStatus status) {
        jdbc.update("UPDATE ai_report_request SET status=?,report_id=? WHERE execution_id=?",
                status.name(), reportPk, executionPk);
    }

    public void fail(long executionPk) {
        jdbc.update("UPDATE ai_report_request SET status='FAILED' WHERE execution_id=?", executionPk);
    }

    public void failExpired() {
        jdbc.update("""
                UPDATE ai_report_request q SET status='FAILED'
                FROM ai_report_execution e WHERE q.execution_id=e.id
                AND e.status='FAILED' AND q.status='GENERATING'
                """);
    }

    public void generating(long executionPk) {
        jdbc.update("UPDATE ai_report_request SET status='GENERATING' WHERE execution_id=?", executionPk);
    }

    private AiReportRequest map(ResultSet row, int ignored) throws SQLException {
        Long executionPk = row.getObject("execution_id", Long.class);
        Long reportPk = row.getObject("report_id", Long.class);
        return new AiReportRequest(row.getLong("id"), row.getObject("ai_request_id", UUID.class),
                row.getLong("fraud_case_id"), executionPk, reportPk,
                row.getString("idempotency_key"), row.getString("fingerprint"),
                row.getInt("detection_result_version"), row.getString("prompt_version"),
                row.getString("model_version"), AiReportStatus.valueOf(row.getString("status")),
                row.getBoolean("cache_hit"), row.getBoolean("execution_shared"),
                row.getString("trace_id"), row.getTimestamp("requested_at").toInstant());
    }
}
