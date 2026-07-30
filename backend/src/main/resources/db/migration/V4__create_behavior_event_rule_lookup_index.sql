CREATE INDEX ix_behavior_event_customer_type_occurred_event
    ON behavior_event (
        external_customer_ref,
        event_type,
        occurred_at DESC,
        event_id ASC
    );
