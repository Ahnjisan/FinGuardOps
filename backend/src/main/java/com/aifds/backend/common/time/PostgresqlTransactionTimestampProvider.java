package com.aifds.backend.common.time;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class PostgresqlTransactionTimestampProvider
        implements DatabaseTransactionTimestampProvider {

    private static final String TRANSACTION_TIMESTAMP_QUERY =
            "SELECT transaction_timestamp()";

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlTransactionTimestampProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Instant currentTransactionTimestamp() {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                TRANSACTION_TIMESTAMP_QUERY,
                Timestamp.class
        );
        if (timestamp == null) {
            throw new IllegalStateException(
                    "PostgreSQL transaction timestamp was not returned"
            );
        }
        return timestamp.toInstant();
    }
}
