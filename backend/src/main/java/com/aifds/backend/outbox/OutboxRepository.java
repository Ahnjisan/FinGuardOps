package com.aifds.backend.outbox;

import com.aifds.backend.aireport.event.AiReportExecutionCreated;
import com.aifds.backend.aireport.event.AiReportExecutionCreatedCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final AiReportExecutionCreatedCodec codec;

    public OutboxRepository(JdbcTemplate jdbc, AiReportExecutionCreatedCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public void insert(AiReportExecutionCreated event) {
        jdbc.update("""
                INSERT INTO ai_report_outbox(event_id,event_type,event_version,execution_id,payload)
                VALUES (?,?,?, ?,?::jsonb)
                """, event.eventId(), event.eventType(), event.eventVersion(),
                event.executionId(), codec.encode(event));
    }

    public Optional<RecoveryRow> recoveryRow(UUID eventId, boolean lock) {
        String sql = """
                SELECT id,event_id,execution_id,event_type,event_version,payload::text,status,
                       attempt_count,last_failure_code,claim_token,lease_until,published_at
                FROM ai_report_outbox WHERE event_id=?
                """ + (lock ? " FOR UPDATE NOWAIT" : "");
        return jdbc.query(sql, (row, ignored) -> new RecoveryRow(
                row.getLong("id"), row.getObject("event_id", UUID.class),
                row.getObject("execution_id", UUID.class), row.getString("event_type"),
                row.getInt("event_version"), row.getString("payload"),
                row.getString("status"), row.getInt("attempt_count"),
                row.getString("last_failure_code"), row.getObject("claim_token", UUID.class),
                row.getTimestamp("lease_until") == null ? null : row.getTimestamp("lease_until").toInstant(),
                row.getTimestamp("published_at") == null ? null : row.getTimestamp("published_at").toInstant()),
                eventId).stream().findFirst();
    }

    public Optional<UUID> eventIdForExecution(UUID executionId) {
        return jdbc.query("SELECT event_id FROM ai_report_outbox WHERE execution_id=?",
                (row, ignored) -> row.getObject(1, UUID.class), executionId).stream().findFirst();
    }

    public boolean requeueBlocked(long id, UUID eventId, UUID executionId) {
        return jdbc.update("""
                UPDATE ai_report_outbox SET status='PENDING',attempt_count=0,next_attempt_at=now(),
                    claim_token=NULL,lease_until=NULL,published_at=NULL,last_failure_code=NULL
                WHERE id=? AND event_id=? AND execution_id=? AND status='BLOCKED'
                  AND claim_token IS NULL AND lease_until IS NULL AND published_at IS NULL
                """, id, eventId, executionId) == 1;
    }

    public Optional<Claim> claim(int leaseSeconds) {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM ai_report_outbox
                  WHERE attempt_count<10 AND ((status='PENDING' AND next_attempt_at<=now())
                     OR (status='CLAIMED' AND lease_until<now()))
                  ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE ai_report_outbox o SET status='CLAIMED',claim_token=?,
                    lease_until=now()+(? * interval '1 second'),
                    attempt_count=o.attempt_count+1
                FROM candidate WHERE o.id=candidate.id
                RETURNING o.id,o.event_id,o.execution_id,o.payload::text,o.claim_token,
                          o.attempt_count
                """, (row, ignored) -> new Claim(row.getLong(1),
                        row.getObject(2, UUID.class), row.getObject(3, UUID.class),
                        row.getString(4), row.getObject(5, UUID.class), row.getInt(6)),
                token, leaseSeconds).stream().findFirst();
    }

    public boolean published(Claim claim) {
        return jdbc.update("""
                UPDATE ai_report_outbox SET status='PUBLISHED',published_at=now(),
                    claim_token=NULL,lease_until=NULL,last_failure_code=NULL
                WHERE id=? AND status='CLAIMED' AND claim_token=?
                """, claim.id(), claim.token()) == 1;
    }

    public boolean failed(Claim claim, String failureCode) {
        int delay = Math.min(60, 1 << Math.min(6, claim.attemptCount() - 1));
        return jdbc.update("""
                UPDATE ai_report_outbox SET status=?,claim_token=NULL,lease_until=NULL,
                    next_attempt_at=now()+(? * interval '1 second'),last_failure_code=?
                WHERE id=? AND status='CLAIMED' AND claim_token=?
                """, claim.attemptCount() >= 10 ? "BLOCKED" : "PENDING",
                delay, failureCode, claim.id(), claim.token()) == 1;
    }

    public long count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_report_outbox WHERE status=?",
                Long.class, status);
    }

    public int blockExpiredExhausted() {
        return jdbc.update("""
                UPDATE ai_report_outbox SET status='BLOCKED',claim_token=NULL,
                    lease_until=NULL,last_failure_code='PUBLISH_ACK_UNCONFIRMED'
                WHERE status='CLAIMED' AND attempt_count>=10 AND lease_until<now()
                """);
    }

    public long oldestPendingSeconds() {
        Long age = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(created_at))::bigint,0)
                FROM ai_report_outbox WHERE status IN ('PENDING','CLAIMED','BLOCKED')
                """, Long.class);
        return age == null ? 0 : age;
    }

    public record Claim(long id, UUID eventId, UUID executionId, String payload,
                        UUID token, int attemptCount) { }
    public record RecoveryRow(long id, UUID eventId, UUID executionId, String eventType,
                              int eventVersion, String payload, String status, int attemptCount,
                              String failureCode, UUID claimToken, Instant leaseUntil,
                              Instant publishedAt) { }
}
