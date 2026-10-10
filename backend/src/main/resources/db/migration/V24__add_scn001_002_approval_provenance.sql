-- Existing APPROVED rows remain NULL: their historical approval time cannot be proven.
ALTER TABLE financial_transaction ADD COLUMN approved_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION set_financial_transaction_approved_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.approved_at IS DISTINCT FROM NEW.approved_at THEN
        RAISE EXCEPTION 'approved_at is database owned and immutable';
    END IF;
    IF OLD.processing_status <> 'APPROVED' AND NEW.processing_status = 'APPROVED' THEN
        IF OLD.approved_at IS NOT NULL THEN
            RAISE EXCEPTION 'approved_at already exists';
        END IF;
        NEW.approved_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_financial_transaction_approved_at
BEFORE UPDATE ON financial_transaction
FOR EACH ROW EXECUTE FUNCTION set_financial_transaction_approved_at();

CREATE INDEX ix_financial_transaction_scn001_baseline
    ON financial_transaction (external_customer_ref, occurred_at DESC,
        transaction_id ASC)
    WHERE processing_status = 'APPROVED'
      AND transaction_type IN ('ACCOUNT_TRANSFER', 'OPEN_BANKING_TRANSFER');
