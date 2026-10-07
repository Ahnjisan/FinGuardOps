package com.aifds.backend.aireport.dlq;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AiReportDlqRepository {
    private final JdbcTemplate jdbc;

    public AiReportDlqRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ActionRow> action(UUID topicId, int partition, long offset) {
        return jdbc.query("""
                SELECT a.id,a.action,a.failure_category,a.event_id,a.execution_id,
                       d.status,d.ack_partition,d.ack_offset
                FROM ai_report_dlq_action a LEFT JOIN ai_report_dlq_replay_dispatch d ON d.action_id=a.id
                WHERE a.dlq_topic_id=? AND a.dlq_partition=? AND a.dlq_offset=?
                """, (row, ignored) -> new ActionRow(row.getLong(1), row.getString(2),
                row.getString(3), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                row.getString(6), (Integer) row.getObject(7), (Long) row.getObject(8)),
                topicId, partition, offset).stream().findFirst();
    }

    public long insertAction(UUID topicId, int partition, long offset, String action,
                             String category, UUID eventId, UUID executionId,
                             UUID actor, String traceId) {
        return jdbc.queryForObject("""
                INSERT INTO ai_report_dlq_action
                    (dlq_topic_id,dlq_partition,dlq_offset,action,failure_category,
                     event_id,execution_id,actor_id,trace_id)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, topicId, partition, offset, action, category,
                eventId, executionId, actor, traceId);
    }

    public void insertIntent(long actionId, UUID eventId, UUID executionId) {
        jdbc.update("""
                INSERT INTO ai_report_dlq_replay_dispatch(action_id,event_id,execution_id)
                VALUES (?,?,?)
                """, actionId, eventId, executionId);
    }

    public Optional<DispatchRow> claim() {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                    SELECT action_id FROM ai_report_dlq_replay_dispatch
                    WHERE attempt_count<10 AND
                      ((status='PENDING' AND next_attempt_at<=now()) OR
                       (status='CLAIMED' AND lease_until<now()))
                    ORDER BY action_id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE ai_report_dlq_replay_dispatch d
                SET status='CLAIMED',claim_token=?,lease_until=now()+interval '30 seconds',
                    attempt_count=d.attempt_count+1
                FROM candidate c WHERE d.action_id=c.action_id
                RETURNING d.action_id,d.event_id,d.execution_id,d.claim_token,d.attempt_count
                """, (row, ignored) -> new DispatchRow(row.getLong(1),
                row.getObject(2, UUID.class), row.getObject(3, UUID.class),
                row.getObject(4, UUID.class), row.getInt(5)), token).stream().findFirst();
    }

    public void ack(DispatchRow claim, int partition, long offset) {
        jdbc.update("""
                UPDATE ai_report_dlq_replay_dispatch SET status='ACKED',claim_token=NULL,
                    lease_until=NULL,ack_partition=?,ack_offset=?,last_failure_code=NULL
                WHERE action_id=? AND status='CLAIMED' AND claim_token=?
                """, partition, offset, claim.actionId(), claim.token());
    }

    public void fail(DispatchRow claim) {
        int delay = Math.min(60, 1 << Math.min(6, claim.attemptCount() - 1));
        jdbc.update("""
                UPDATE ai_report_dlq_replay_dispatch SET status=?,claim_token=NULL,
                    lease_until=NULL,next_attempt_at=now()+(? * interval '1 second'),
                    last_failure_code='PUBLISH_UNCONFIRMED'
                WHERE action_id=? AND status='CLAIMED' AND claim_token=?
                """, claim.attemptCount() >= 10 ? "BLOCKED" : "PENDING",
                delay, claim.actionId(), claim.token());
    }

    public void skip(DispatchRow claim) {
        jdbc.update("""
                UPDATE ai_report_dlq_replay_dispatch SET status='SKIPPED',claim_token=NULL,
                    lease_until=NULL,last_failure_code=NULL
                WHERE action_id=? AND status='CLAIMED' AND claim_token=?
                """, claim.actionId(), claim.token());
    }

    public void blockExpiredExhausted() {
        jdbc.update("""
                UPDATE ai_report_dlq_replay_dispatch SET status='BLOCKED',claim_token=NULL,
                    lease_until=NULL,last_failure_code='PUBLISH_UNCONFIRMED'
                WHERE status='CLAIMED' AND attempt_count>=10 AND lease_until<now()
                """);
    }

    public Optional<String> startSource(UUID executionId) {
        return jdbc.query("SELECT source FROM ai_report_execution_start_source WHERE execution_id=?",
                (row, ignored) -> row.getString(1), executionId).stream().findFirst();
    }

    public record ActionRow(long id, String action, String category, UUID eventId,
                            UUID executionId, String dispatchStatus, Integer ackPartition,
                            Long ackOffset) { }
    public record DispatchRow(long actionId, UUID eventId, UUID executionId,
                              UUID token, int attemptCount) { }
}
