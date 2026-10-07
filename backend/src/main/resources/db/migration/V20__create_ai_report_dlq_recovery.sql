CREATE TABLE ai_report_dlq_action (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dlq_topic_id UUID NOT NULL,
    dlq_partition INTEGER NOT NULL CHECK (dlq_partition >= 0),
    dlq_offset BIGINT NOT NULL CHECK (dlq_offset >= 0),
    action VARCHAR(16) NOT NULL CHECK (action IN ('QUARANTINE','REPLAY')),
    failure_category VARCHAR(32) NOT NULL,
    event_id UUID,
    execution_id UUID,
    actor_id UUID NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_dlq_coordinate UNIQUE (dlq_topic_id, dlq_partition, dlq_offset),
    CONSTRAINT ck_ai_dlq_replay_identity CHECK
        (action <> 'REPLAY' OR (event_id IS NOT NULL AND execution_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_ai_dlq_replay_event ON ai_report_dlq_action(event_id)
    WHERE action = 'REPLAY';

CREATE FUNCTION reject_ai_report_dlq_action_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ai_report_dlq_action is append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER tr_ai_report_dlq_action_reject_mutation
    BEFORE UPDATE OR DELETE ON ai_report_dlq_action
    FOR EACH ROW EXECUTE FUNCTION reject_ai_report_dlq_action_mutation();

CREATE TABLE ai_report_dlq_replay_dispatch (
    action_id BIGINT PRIMARY KEY REFERENCES ai_report_dlq_action(id) ON DELETE RESTRICT,
    event_id UUID NOT NULL UNIQUE REFERENCES ai_report_outbox(event_id) ON DELETE RESTRICT,
    execution_id UUID NOT NULL REFERENCES ai_report_execution(execution_id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','CLAIMED','ACKED','BLOCKED','SKIPPED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 10),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    ack_partition INTEGER,
    ack_offset BIGINT,
    last_failure_code VARCHAR(32),
    CONSTRAINT ck_ai_dlq_dispatch_claim CHECK
        ((status='CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
         OR (status<>'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_ai_dlq_dispatch_ack CHECK
        ((status='ACKED' AND ack_partition IS NOT NULL AND ack_offset IS NOT NULL)
         OR (status<>'ACKED' AND ack_partition IS NULL AND ack_offset IS NULL))
);
CREATE INDEX ix_ai_dlq_dispatch_ready ON ai_report_dlq_replay_dispatch(next_attempt_at, action_id)
    WHERE status IN ('PENDING','CLAIMED');

CREATE TABLE ai_report_execution_start_source (
    execution_id UUID PRIMARY KEY REFERENCES ai_report_execution(execution_id) ON DELETE RESTRICT,
    source VARCHAR(8) NOT NULL CHECK (source IN ('KAFKA','POLLING')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
