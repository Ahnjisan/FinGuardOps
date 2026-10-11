-- Legacy APPROVED rows with NULL remain UNKNOWN; no approval time is backfilled.
CREATE OR REPLACE FUNCTION reject_explicit_financial_approval_time()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.approved_at IS NOT NULL THEN
        RAISE EXCEPTION 'approved_at is database owned';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_financial_transaction_approval_insert_guard
BEFORE INSERT ON financial_transaction
FOR EACH ROW EXECUTE FUNCTION reject_explicit_financial_approval_time();

-- The new Mock source always uses the receiving database clock, including
-- privileged test fixtures that supply a created_at value explicitly.
CREATE OR REPLACE FUNCTION assign_fds_mock_fact_created_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_fds_mock_fact_created_at
BEFORE INSERT ON fds_mock_fact
FOR EACH ROW EXECUTE FUNCTION assign_fds_mock_fact_created_at();

-- Existing behavior-event intake has no correction command. Its event IDs are
-- now used as immutable evidence anchors by the newly published Rule set.
CREATE TRIGGER tr_behavior_event_immutable
BEFORE UPDATE OR DELETE ON behavior_event
FOR EACH ROW EXECUTE FUNCTION reject_scenario_history_mutation();
