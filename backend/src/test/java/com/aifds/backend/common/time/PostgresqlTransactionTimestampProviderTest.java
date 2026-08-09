package com.aifds.backend.common.time;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlTransactionTimestampProviderTest {

    @Test
    void returnsPostgresqlTransactionTimestampAsInstant() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Instant transactionTimestamp =
                Instant.parse("2026-08-09T06:05:08.352971Z");
        when(jdbcTemplate.queryForObject(
                "SELECT transaction_timestamp()",
                Timestamp.class
        )).thenReturn(Timestamp.from(transactionTimestamp));

        Instant actual = new PostgresqlTransactionTimestampProvider(
                jdbcTemplate
        ).currentTransactionTimestamp();

        assertThat(actual).isEqualTo(transactionTimestamp);
        verify(jdbcTemplate).queryForObject(
                "SELECT transaction_timestamp()",
                Timestamp.class
        );
    }
}
