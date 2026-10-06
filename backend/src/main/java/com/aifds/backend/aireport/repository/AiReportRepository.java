package com.aifds.backend.aireport.repository;

import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.entity.AiReport;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AiReportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AiReportRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<AiReport> exact(long casePk, int version, String prompt, String model) {
        return jdbc.query("""
                SELECT id,report_id,execution_id FROM ai_report WHERE fraud_case_id=?
                AND detection_result_version=? AND prompt_version=? AND model_version=?
                """, (row, ignored) -> new AiReport(row.getLong(1), row.getObject(2, UUID.class), row.getLong(3)),
                casePk, version, prompt, model).stream().findFirst();
    }

    public AiReport insert(long casePk, long executionPk, int version, String prompt,
                           String model, AiReportDtos.GenerationResult result, String traceId) {
        UUID id = UUID.randomUUID();
        AiReportDtos.Content content = result.content();
        try {
            jdbc.update("""
                    INSERT INTO ai_report(report_id,fraud_case_id,execution_id,detection_result_version,
                    prompt_version,model_version,report_status,report_source,summary,key_reasons,
                    timeline_summary,investigation_checklist,failure_code,trace_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?)
                    """, id, casePk, executionPk, version, prompt, model, result.status(),
                    result.source(), content.summary(), json.writeValueAsString(content.keyReasons()),
                    "공개된 행동 타임라인 자료가 없어 요약하지 않았습니다.",
                    json.writeValueAsString(content.investigationChecklist()), result.failureCode(), traceId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Report serialization failed", exception);
        }
        return exact(casePk, version, prompt, model).orElseThrow();
    }

    public Optional<AiReportDtos.Report> current(long casePk) {
        return jdbc.query("""
                SELECT r.*,e.execution_id AS public_execution_id,e.fallback_trigger_code,
                       q.ai_request_id AS initiating_request_id,c.case_id AS public_case_id
                FROM ai_report r
                JOIN ai_report_execution e ON e.id=r.execution_id
                JOIN fraud_case c ON c.id=r.fraud_case_id
                JOIN ai_report_request q ON q.execution_id=e.id
                    AND q.execution_shared=false AND q.cache_hit=false
                WHERE r.fraud_case_id=?
                ORDER BY q.requested_at DESC,q.ai_request_id DESC LIMIT 1
                """, this::map, casePk).stream().findFirst();
    }

    public Optional<AiReportDtos.Report> byPk(long pk) {
        return jdbc.query("""
                SELECT r.*,e.execution_id AS public_execution_id,e.fallback_trigger_code,
                       q.ai_request_id AS initiating_request_id,c.case_id AS public_case_id
                FROM ai_report r JOIN ai_report_execution e ON e.id=r.execution_id
                JOIN fraud_case c ON c.id=r.fraud_case_id
                JOIN ai_report_request q ON q.execution_id=e.id
                    AND q.execution_shared=false AND q.cache_hit=false
                WHERE r.id=? LIMIT 1
                """, this::map, pk).stream().findFirst();
    }

    private AiReportDtos.Report map(ResultSet row, int ignored) throws SQLException {
        try {
            List<AiReportDtos.KeyReason> reasons = json.readValue(row.getString("key_reasons"),
                    new TypeReference<>() { });
            List<String> checklist = json.readValue(row.getString("investigation_checklist"),
                    new TypeReference<>() { });
            return new AiReportDtos.Report(row.getObject("report_id", UUID.class),
                    row.getObject("public_execution_id", UUID.class),
                    row.getObject("initiating_request_id", UUID.class),
                    row.getObject("public_case_id", UUID.class), row.getInt("detection_result_version"),
                    row.getString("report_status"), row.getString("report_source"),
                    row.getString("summary"), reasons, row.getString("timeline_summary"), checklist,
                    row.getString("prompt_version"), row.getString("model_version"),
                    row.getTimestamp("generated_at").toInstant(), row.getString("failure_code"),
                    row.getString("fallback_trigger_code"),
                    row.getString("trace_id"));
        } catch (java.io.IOException exception) {
            throw new SQLException("Stored report is invalid", exception);
        }
    }
}
