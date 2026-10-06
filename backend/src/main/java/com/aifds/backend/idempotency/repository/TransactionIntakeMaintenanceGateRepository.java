package com.aifds.backend.idempotency.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionIntakeMaintenanceGateRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionIntakeMaintenanceGateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isOpen() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT NOT closed FROM transaction_intake_maintenance_gate WHERE id = 1",
                Boolean.class
        ));
    }

    public boolean isOpenForShare() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT NOT closed FROM transaction_intake_maintenance_gate WHERE id = 1 FOR SHARE",
                Boolean.class
        ));
    }

    public void setClosed(boolean closed) {
        if (jdbcTemplate.update(
                "UPDATE transaction_intake_maintenance_gate SET closed = ?, changed_at = clock_timestamp() WHERE id = 1",
                closed
        ) != 1) {
            throw new IllegalStateException("Maintenance gate row is missing");
        }
    }

    public int activeWebSessions() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT CASE
                    WHEN pg_has_role(current_user, 'pg_read_all_stats', 'member')
                         OR (SELECT usesuper FROM pg_user WHERE usename = current_user)
                    THEN count(*)
                    ELSE -1
                END
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND application_name = 'finguardops-web'
                  AND pid <> pg_backend_pid()
                """, Integer.class);
        return count == null ? -1 : count;
    }
}
