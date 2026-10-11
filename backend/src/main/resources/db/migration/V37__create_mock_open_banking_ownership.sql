-- Immutable local Mock registration and ownership assertions for external accounts.
CREATE TABLE fds_mock_open_account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assertion_id UUID NOT NULL UNIQUE,
    external_customer_ref VARCHAR(128) NOT NULL,
    external_account_ref VARCHAR(128) NOT NULL,
    registration_state VARCHAR(16) NOT NULL,
    ownership_state VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source_code VARCHAR(32) NOT NULL DEFAULT 'LOCAL_OPEN_BANKING_MOCK',
    source_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    request_fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT ck_fds_mock_open_account_id CHECK (
        get_byte(uuid_send(assertion_id), 6) BETWEEN 64 AND 79
        AND get_byte(uuid_send(assertion_id), 8) BETWEEN 128 AND 191),
    CONSTRAINT ck_fds_mock_open_account_refs CHECK (
        char_length(external_customer_ref) BETWEEN 1 AND 128
        AND external_customer_ref = btrim(external_customer_ref)
        AND char_length(external_account_ref) BETWEEN 1 AND 128
        AND external_account_ref = btrim(external_account_ref)),
    CONSTRAINT ck_fds_mock_open_account_states CHECK (
        registration_state IN ('VERIFIED','PARTIAL','FAILED')
        AND ownership_state IN ('VERIFIED','PARTIAL','FAILED')),
    CONSTRAINT ck_fds_mock_open_account_time CHECK (
        occurred_at <= created_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_fds_mock_open_account_source CHECK (
        source_code = 'LOCAL_OPEN_BANKING_MOCK' AND source_version = 'v1'),
    CONSTRAINT ck_fds_mock_open_account_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX uq_fds_mock_open_account_semantic
    ON fds_mock_open_account (external_account_ref, occurred_at);
CREATE INDEX ix_fds_mock_open_account_cutoff
    ON fds_mock_open_account (external_account_ref, occurred_at DESC,
        created_at DESC, assertion_id);

CREATE OR REPLACE FUNCTION guard_fds_mock_open_account_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Mock open account assertion is immutable';
END;
$$;
CREATE TRIGGER tr_fds_mock_open_account_immutable
BEFORE UPDATE OR DELETE ON fds_mock_open_account
FOR EACH ROW EXECUTE FUNCTION guard_fds_mock_open_account_immutable();
