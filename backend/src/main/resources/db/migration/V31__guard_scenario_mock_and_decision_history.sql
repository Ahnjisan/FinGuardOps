-- Scenario assertions and post-decision commands are immutable evidence.
CREATE FUNCTION reject_scenario_history_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'scenario history is immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER tr_fds_mock_fact_reject_mutation
BEFORE UPDATE OR DELETE ON fds_mock_fact
FOR EACH ROW EXECUTE FUNCTION reject_scenario_history_mutation();

CREATE TRIGGER tr_fds_mock_auth_decision_reject_mutation
BEFORE UPDATE OR DELETE ON fds_mock_auth_decision
FOR EACH ROW EXECUTE FUNCTION reject_scenario_history_mutation();

CREATE TRIGGER tr_scenario_case_correlation_reject_mutation
BEFORE UPDATE OR DELETE ON scenario_case_correlation
FOR EACH ROW EXECUTE FUNCTION reject_scenario_history_mutation();
