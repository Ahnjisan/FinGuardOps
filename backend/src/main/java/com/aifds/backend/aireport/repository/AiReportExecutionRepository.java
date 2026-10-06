package com.aifds.backend.aireport.repository;

import com.aifds.backend.aireport.entity.AiReportExecution;
import com.aifds.backend.aireport.entity.AiReportStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import com.aifds.backend.aireport.event.AiReportExecutionCreated;

@Repository
public class AiReportExecutionRepository {
    private final JdbcTemplate jdbc;

    public AiReportExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AiReportExecution> active(long casePk, int version, String prompt, String model) {
        return jdbc.query("""
                SELECT * FROM ai_report_execution WHERE fraud_case_id=? AND detection_result_version=?
                AND prompt_version=? AND model_version=? AND status IN ('PENDING','GENERATING') LIMIT 1
                """, this::map, casePk, version, prompt, model).stream().findFirst();
    }

    public AiReportExecution insert(long casePk, long detectionPk, int version, String prompt, String model) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_report_execution(execution_id,fraud_case_id,detection_result_id,
                detection_result_version,prompt_version,model_version,status)
                VALUES (?,?,?,?,?,?,'PENDING')
                """, id, casePk, detectionPk, version, prompt, model);
        return jdbc.query("SELECT * FROM ai_report_execution WHERE execution_id=?", this::map, id).get(0);
    }

    public Optional<AiReportExecution> claim(long leaseSeconds) {
        return claimInternal(null, leaseSeconds);
    }

    public Optional<AiReportExecution> claim(UUID executionId, long leaseSeconds) {
        return claimInternal(executionId, leaseSeconds);
    }

    private Optional<AiReportExecution> claimInternal(UUID executionId, long leaseSeconds) {
        // Serialize all worker claims across Spring instances. The transaction-scoped
        // advisory lock is released at commit; the GENERATING row remains the
        // durable one-at-a-time semaphore until completion or lease expiry.
        jdbc.execute("SELECT pg_advisory_xact_lock(339)");
        Integer generating = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_report_execution WHERE status='GENERATING'", Integer.class);
        if (generating == null || generating > 0) return Optional.empty();
        String selector = executionId == null
                ? "SELECT id FROM ai_report_execution WHERE status='PENDING' "
                    + "ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1"
                : "SELECT id FROM ai_report_execution WHERE execution_id=? AND status='PENDING' "
                    + "FOR UPDATE SKIP LOCKED";
        String sql = """
                UPDATE ai_report_execution SET status='GENERATING',
                lease_until=now() + (? * interval '1 second')
                WHERE id=(%s) AND status='PENDING'
                RETURNING *
                """.formatted(selector);
        return (executionId == null
                ? jdbc.query(sql, this::map, leaseSeconds)
                : jdbc.query(sql, this::map, leaseSeconds, executionId))
                .stream().findFirst();
    }

    public Optional<AiReportStatus> status(UUID executionId) {
        return jdbc.query("SELECT status FROM ai_report_execution WHERE execution_id=?",
                (row, ignored) -> AiReportStatus.valueOf(row.getString(1)), executionId)
                .stream().findFirst();
    }

    public boolean matches(AiReportExecutionCreated event) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ai_report_execution e
                JOIN fraud_case c ON c.id=e.fraud_case_id
                JOIN ai_report_request q ON q.execution_id=e.id
                JOIN ai_report_outbox o ON o.execution_id=e.execution_id
                WHERE e.execution_id=? AND o.event_id=? AND c.case_id=? AND q.ai_request_id=?
                  AND q.execution_shared=false AND q.cache_hit=false
                  AND e.detection_result_version=? AND e.prompt_version=?
                  AND e.model_version=? AND q.trace_id=?
                """, Integer.class, event.executionId(), event.eventId(), event.caseId(),
                event.initiatingAiRequestId(), event.detectionResultVersion(),
                event.promptVersion(), event.modelVersion(), event.traceId());
        return count != null && count == 1;
    }

    public void complete(long pk, AiReportStatus status, String failure, String fallbackTrigger) {
        jdbc.update("UPDATE ai_report_execution SET status=?,failure_code=?,fallback_trigger_code=?,lease_until=NULL,finished_at=now() WHERE id=? AND status='GENERATING'",
                status.name(), failure, fallbackTrigger, pk);
    }

    public boolean stillGenerating(long pk) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT status='GENERATING' AND lease_until > now() FROM ai_report_execution WHERE id=? FOR UPDATE",
                Boolean.class, pk));
    }

    public void expireLeases() {
        jdbc.update("""
                UPDATE ai_report_execution SET status='FAILED',failure_code='WORKER_INTERRUPTED',
                lease_until=NULL,finished_at=now()
                WHERE status='GENERATING' AND lease_until < now()
                """);
    }

    private AiReportExecution map(ResultSet row, int ignored) throws SQLException {
        return new AiReportExecution(row.getLong("id"), row.getObject("execution_id", UUID.class),
                row.getLong("fraud_case_id"), row.getLong("detection_result_id"),
                row.getInt("detection_result_version"), row.getString("prompt_version"),
                row.getString("model_version"), AiReportStatus.valueOf(row.getString("status")));
    }
}
