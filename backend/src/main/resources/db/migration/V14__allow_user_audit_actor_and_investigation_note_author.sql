ALTER TABLE investigation_note
    DROP CONSTRAINT ck_investigation_note_author,
    ADD CONSTRAINT ck_investigation_note_author CHECK (
        author_type IS NOT NULL
        AND author_ref IS NOT NULL
        AND (
            (
                author_type = 'SYSTEM'
                AND author_ref = 'finguardops-backend'
            )
            OR (
                author_type = 'USER'
                AND author_ref
                    ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            )
        )
    );

ALTER TABLE audit_log
    DROP CONSTRAINT ck_audit_log_case_note_actor,
    ADD CONSTRAINT ck_audit_log_case_note_actor CHECK (
        action <> 'CASE_NOTE_CREATED'
        OR (
            actor_type IS NOT NULL
            AND actor_id IS NOT NULL
            AND (
                (
                    actor_type = 'SYSTEM'
                    AND actor_id = 'finguardops-backend'
                )
                OR (
                    actor_type = 'USER'
                    AND actor_id
                        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                )
            )
        )
    );
