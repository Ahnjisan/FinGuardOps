-- Read-only as-of windows for SCN-004 (outbound) and SCN-005 (inbound).
ALTER TABLE fds_mock_fact DROP CONSTRAINT ck_fds_mock_fact_type;
ALTER TABLE fds_mock_fact ADD CONSTRAINT ck_fds_mock_fact_type CHECK (fact_type IN (
    'TRANSFER_HISTORY_COMPLETE', 'INBOUND_ACCOUNT_HISTORY_COMPLETE',
    'DEVICE_HISTORY_COMPLETE', 'DEVICE_TRUST', 'LIMIT_CHANGE',
    'IDENTITY_CHECK', 'APP_SECURITY', 'NETWORK_REGION'));

CREATE INDEX ix_fds_mock_fact_inbound_coverage
    ON fds_mock_fact (account_ref, coverage_from, coverage_through)
    WHERE fact_type = 'INBOUND_ACCOUNT_HISTORY_COMPLETE';

CREATE INDEX ix_financial_transaction_scn004_outbound
    ON financial_transaction (external_customer_ref, sender_account_ref,
        occurred_at DESC, transaction_id ASC)
    WHERE processing_status = 'APPROVED'
      AND currency_code = 'KRW'
      AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER');

CREATE INDEX ix_financial_transaction_scn005_inbound
    ON financial_transaction (recipient_account_ref, occurred_at DESC,
        transaction_id ASC)
    WHERE processing_status = 'APPROVED'
      AND currency_code = 'KRW'
      AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER');
