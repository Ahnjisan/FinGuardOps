package com.aifds.backend.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxRecoveryRepository {
    private final JdbcTemplate jdbc;

    public OutboxRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ExecutionRow> execution(UUID id, boolean lock) {
        return jdbc.query("""
                SELECT e.id,e.execution_id,e.status,e.failure_code,e.lease_until,
                       e.detection_result_version,e.prompt_version,e.model_version,c.case_id,c.id
                FROM ai_report_execution e JOIN fraud_case c ON c.id=e.fraud_case_id
                WHERE e.execution_id=?
                """ + (lock ? " FOR UPDATE OF e NOWAIT" : ""), (row, ignored) ->
                new ExecutionRow(row.getLong(1), row.getObject(2, UUID.class), row.getString(3),
                        row.getString(4), row.getObject(5) != null, row.getInt(6),
                        row.getString(7), row.getString(8), row.getObject(9, UUID.class),
                        row.getLong(10)), id)
                .stream().findFirst();
    }

    public List<RequestRow> requests(long executionPk, boolean lock) {
        return jdbc.query("""
                SELECT ai_request_id,status,execution_shared,cache_hit,report_id,trace_id,
                       detection_result_version,prompt_version,model_version,fraud_case_id
                FROM ai_report_request WHERE execution_id=? ORDER BY id
                """ + (lock ? " FOR UPDATE NOWAIT" : ""), (row, ignored) ->
                new RequestRow(row.getObject(1, UUID.class), row.getString(2),
                        row.getBoolean(3), row.getBoolean(4), row.getObject(5) != null,
                        row.getString(6), row.getInt(7), row.getString(8), row.getString(9),
                        row.getLong(10)),
                executionPk);
    }

    public boolean reportExists(long executionPk) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ai_report WHERE execution_id=?)", Boolean.class, executionPk));
    }

    public boolean attemptExists(long executionPk) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM provider_call_attempt WHERE execution_id=?)",
                Boolean.class, executionPk));
    }

    public boolean alreadyRequeued(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ai_report_outbox_requeue_log WHERE event_id=?)",
                Boolean.class, eventId));
    }

    public void record(UUID eventId, UUID executionId, UUID actorId, String traceId) {
        jdbc.update("""
                INSERT INTO ai_report_outbox_requeue_log
                    (event_id,execution_id,actor_id,before_status,after_status,trace_id)
                VALUES (?,?,?,'BLOCKED','PENDING',?)
                """, eventId, executionId, actorId, traceId);
    }

    public record ExecutionRow(long id, UUID executionId, String status, String failureCode,
                               boolean leased, int detectionResultVersion, String promptVersion,
                               String modelVersion, UUID caseId, long casePk) { }
    public record RequestRow(UUID aiRequestId, String status, boolean shared, boolean cacheHit,
                             boolean reportLinked, String traceId, int detectionResultVersion,
                             String promptVersion, String modelVersion, long casePk) { }
}
