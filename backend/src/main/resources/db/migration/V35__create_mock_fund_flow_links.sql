-- Authenticated local Mock declarations of a specific source-to-use relationship.
-- Account ownership is asserted by this source and independently checked against
-- both immutable transaction identifiers at the evaluation cutoff.
ALTER TABLE fds_mock_fact DROP CONSTRAINT ck_fds_mock_fact_type;
ALTER TABLE fds_mock_fact ADD CONSTRAINT ck_fds_mock_fact_type CHECK (fact_type IN (
    'TRANSFER_HISTORY_COMPLETE', 'INBOUND_ACCOUNT_HISTORY_COMPLETE',
    'ACCOUNT_FLOW_HISTORY_COMPLETE', 'DEVICE_HISTORY_COMPLETE',
    'DEVICE_TRUST', 'LIMIT_CHANGE', 'IDENTITY_CHECK',
    'APP_SECURITY', 'NETWORK_REGION'));

CREATE TABLE fds_mock_fund_link (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id UUID NOT NULL UNIQUE,
    scenario_code VARCHAR(8) NOT NULL,
    source_transaction_id UUID NOT NULL,
    target_transaction_id UUID NOT NULL,
    external_customer_ref VARCHAR(128) NOT NULL,
    account_ref VARCHAR(128) NOT NULL,
    assertion_state VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source_code VARCHAR(32) NOT NULL DEFAULT 'LOCAL_FDS_MOCK',
    source_version VARCHAR(16) NOT NULL DEFAULT 'v2',
    request_fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT ck_fds_mock_fund_link_scenario CHECK
        (scenario_code IN ('SCN006', 'SCN007', 'SCN008')),
    CONSTRAINT ck_fds_mock_fund_link_identity CHECK
        (source_transaction_id <> target_transaction_id
         AND get_byte(uuid_send(link_id), 6) BETWEEN 64 AND 79
         AND get_byte(uuid_send(link_id), 8) BETWEEN 128 AND 191
         AND get_byte(uuid_send(source_transaction_id), 6) BETWEEN 64 AND 79
         AND get_byte(uuid_send(source_transaction_id), 8) BETWEEN 128 AND 191
         AND get_byte(uuid_send(target_transaction_id), 6) BETWEEN 64 AND 79
         AND get_byte(uuid_send(target_transaction_id), 8) BETWEEN 128 AND 191),
    CONSTRAINT ck_fds_mock_fund_link_refs CHECK
        (char_length(external_customer_ref) BETWEEN 1 AND 128
         AND external_customer_ref = btrim(external_customer_ref)
         AND char_length(account_ref) BETWEEN 1 AND 128
         AND account_ref = btrim(account_ref)),
    CONSTRAINT ck_fds_mock_fund_link_state CHECK
        (assertion_state IN ('VERIFIED', 'FAILED', 'PARTIAL')),
    CONSTRAINT ck_fds_mock_fund_link_time CHECK
        (occurred_at <= created_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_fds_mock_fund_link_source CHECK
        (source_code = 'LOCAL_FDS_MOCK' AND source_version = 'v2'),
    CONSTRAINT ck_fds_mock_fund_link_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_fds_mock_fund_link_semantic
    ON fds_mock_fund_link (scenario_code, source_transaction_id,
        target_transaction_id, external_customer_ref, account_ref);
CREATE INDEX ix_fds_mock_fund_link_target_cutoff
    ON fds_mock_fund_link (scenario_code, target_transaction_id,
        occurred_at DESC, created_at DESC, link_id);
CREATE INDEX ix_fds_mock_fund_link_source_cutoff
    ON fds_mock_fund_link (scenario_code, source_transaction_id,
        occurred_at DESC, created_at DESC, link_id);

CREATE OR REPLACE FUNCTION guard_fds_mock_fund_link_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Mock fund link is immutable';
END;
$$;
CREATE TRIGGER tr_fds_mock_fund_link_immutable
BEFORE UPDATE OR DELETE ON fds_mock_fund_link
FOR EACH ROW EXECUTE FUNCTION guard_fds_mock_fund_link_immutable();
