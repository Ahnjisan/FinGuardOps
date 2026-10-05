package com.aifds.backend.aireport.repository;

import com.aifds.backend.aireport.dto.AiReportDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderCallAttemptRepository {
    private final JdbcTemplate jdbc;

    public ProviderCallAttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(long executionPk, int number, AiReportDtos.Attempt attempt) {
        jdbc.update("""
                INSERT INTO provider_call_attempt(execution_id,attempt_number,provider,model_digest,
                quantization,input_tokens,output_tokens,latency_ms,outcome,estimated_cost,cost_currency)
                VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL)
                """, executionPk, number, attempt.provider(), attempt.modelDigest(),
                attempt.quantization(), attempt.inputTokens(), attempt.outputTokens(),
                attempt.latencyMs(), attempt.outcome());
    }
}
