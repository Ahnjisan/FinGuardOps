CREATE TABLE ai_report_outbox_requeue_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES ai_report_outbox(event_id) ON DELETE RESTRICT,
    execution_id UUID NOT NULL REFERENCES ai_report_execution(execution_id) ON DELETE RESTRICT,
    actor_id UUID NOT NULL,
    before_status VARCHAR(16) NOT NULL CHECK (before_status = 'BLOCKED'),
    after_status VARCHAR(16) NOT NULL CHECK (after_status = 'PENDING'),
    trace_id VARCHAR(64) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION reject_ai_report_outbox_requeue_log_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ai_report_outbox_requeue_log is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER tr_ai_report_outbox_requeue_log_reject_mutation
    BEFORE UPDATE OR DELETE ON ai_report_outbox_requeue_log
    FOR EACH ROW EXECUTE FUNCTION reject_ai_report_outbox_requeue_log_mutation();
