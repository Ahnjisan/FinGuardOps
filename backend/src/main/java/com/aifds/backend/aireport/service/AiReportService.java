package com.aifds.backend.aireport.service;

import com.aifds.backend.aireport.client.AiReportHttpClient;
import com.aifds.backend.aireport.dto.AiReportDtos;
import com.aifds.backend.aireport.entity.AiReportExecution;
import com.aifds.backend.aireport.entity.AiReportRequest;
import com.aifds.backend.aireport.entity.AiReportStatus;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.repository.AiReportExecutionRepository;
import com.aifds.backend.aireport.repository.AiReportRepository;
import com.aifds.backend.aireport.repository.AiReportRequestRepository;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.security.principal.CurrentAuditActorProvider;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AiReportService {
    private final FraudCaseRepository cases;
    private final JdbcTemplate jdbc;
    private final AiReportInputProjection projection;
    private final AiReportHttpClient client;
    private final AiReportRequestRepository requests;
    private final AiReportExecutionRepository executions;
    private final AiReportRepository reports;
    private final IdempotencyKeyValidator keys;
    private final CurrentAuditActorProvider actors;
    private final EntityManager entityManager;

    public AiReportService(FraudCaseRepository cases, JdbcTemplate jdbc, AiReportInputProjection projection,
                           AiReportHttpClient client, AiReportRequestRepository requests,
                           AiReportExecutionRepository executions, AiReportRepository reports,
                           IdempotencyKeyValidator keys, CurrentAuditActorProvider actors,
                           EntityManager entityManager) {
        this.cases = cases;
        this.jdbc = jdbc;
        this.projection = projection;
        this.client = client;
        this.requests = requests;
        this.executions = executions;
        this.reports = reports;
        this.keys = keys;
        this.actors = actors;
        this.entityManager = entityManager;
    }

    @Transactional
    public CreateOutcome create(UUID caseId, String idempotencyKey,
                                AiReportDtos.CreateRequest body, String traceId) {
        String key = keys.validate(idempotencyKey);
        if (body == null || body.detectionResultVersion() < 1 ||
                (body.regenerationReason() != null && body.regenerationReason().length() > 240)) {
            throw new AiReportException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        FraudCase fraudCase = cases.findByCaseId(caseId)
                .orElseThrow(() -> new AiReportException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
        jdbc.queryForObject("SELECT id FROM fraud_case WHERE id=? FOR UPDATE", Long.class, fraudCase.getId());
        entityManager.refresh(fraudCase);
        String fingerprint = fingerprint(caseId, body);
        var existing = requests.byKey(fraudCase.getId(), key);
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw new AiReportException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");
            }
            return new CreateOutcome(status(existing.get()), !terminal(existing.get().status()));
        }
        if (fraudCase.getCaseStatus() != FraudCaseStatus.IN_REVIEW) {
            throw new AiReportException(HttpStatus.CONFLICT, "CASE_STATUS_CONFLICT");
        }
        var input = projection.project(fraudCase, body.detectionResultVersion(), traceId);
        AiReportDtos.ModelIdentity identity;
        try {
            identity = client.identity();
        } catch (RuntimeException exception) {
            throw new AiReportException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
        if (identity == null || identity.modelVersion() == null || identity.promptVersion() == null) {
            throw new AiReportException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
        if (body.regenerationReason() != null && !body.regenerationReason().isBlank()
                && reports.exact(fraudCase.getId(), body.detectionResultVersion(),
                identity.promptVersion(), identity.modelVersion()).isPresent()) {
            throw new AiReportException(HttpStatus.CONFLICT, "STATE_TRANSITION_NOT_ALLOWED");
        }
        var cached = reports.exact(fraudCase.getId(), body.detectionResultVersion(),
                identity.promptVersion(), identity.modelVersion());
        if (cached.isPresent()) {
            Long recordedDetection = jdbc.queryForObject(
                    "SELECT detection_result_id FROM ai_report_execution WHERE id=?",
                    Long.class, cached.get().executionPk());
            if (recordedDetection == null || recordedDetection != input.detectionPk()) {
                throw new AiReportException(HttpStatus.CONFLICT, "STATE_TRANSITION_NOT_ALLOWED");
            }
        }
        var active = cached.isPresent() ? java.util.Optional.<AiReportExecution>empty()
                : executions.active(fraudCase.getId(), body.detectionResultVersion(),
                identity.promptVersion(), identity.modelVersion());
        if (active.isPresent() && active.get().detectionPk() != input.detectionPk()) {
            throw new AiReportException(HttpStatus.CONFLICT, "STATE_TRANSITION_NOT_ALLOWED");
        }
        AiReportExecution execution = cached.isPresent() ? null : active.orElseGet(() ->
                executions.insert(fraudCase.getId(), input.detectionPk(), body.detectionResultVersion(),
                        identity.promptVersion(), identity.modelVersion()));
        AiReportStatus status = cached.isPresent()
                ? AiReportStatus.valueOf(reports.byPk(cached.get().id()).orElseThrow().reportStatus())
                : execution.status();
        AiReportRequest created = requests.insert(UUID.randomUUID(), fraudCase.getId(),
                execution == null ? null : execution.id(), cached.map(com.aifds.backend.aireport.entity.AiReport::id).orElse(null),
                key, fingerprint, actors.currentUserSubject().toString(), body.detectionResultVersion(),
                identity.promptVersion(), identity.modelVersion(), status,
                cached.isPresent(), active.isPresent(), traceId);
        return new CreateOutcome(status(created), !terminal(status));
    }

    @Transactional(readOnly = true)
    public AiReportDtos.Current current(UUID caseId, String traceId) {
        FraudCase fraudCase = cases.findByCaseId(caseId)
                .orElseThrow(() -> new AiReportException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
        return new AiReportDtos.Current(caseId, reports.current(fraudCase.getId()).orElse(null),
                requests.latest(fraudCase.getId()).map(this::status).orElse(null), traceId);
    }

    private AiReportDtos.RequestStatus status(AiReportRequest request) {
        var report = request.reportPk() == null ? java.util.Optional.<AiReportDtos.Report>empty()
                : reports.byPk(request.reportPk());
        UUID executionId = request.executionPk() == null ? null : jdbc.queryForObject(
                "SELECT execution_id FROM ai_report_execution WHERE id=?", UUID.class, request.executionPk());
        UUID caseId = jdbc.queryForObject("SELECT case_id FROM fraud_case WHERE id=?",
                UUID.class, request.casePk());
        UUID initiating = request.executionPk() == null ? null :
                requests.initiators(request.executionPk()).get(0).aiRequestId();
        UUID source = request.cacheHit()
                ? report.map(AiReportDtos.Report::initiatingAiRequestId).orElse(null) : null;
        String failure = request.executionPk() == null ? null : jdbc.queryForObject(
                "SELECT failure_code FROM ai_report_execution WHERE id=?", String.class, request.executionPk());
        return new AiReportDtos.RequestStatus(request.aiRequestId(), executionId,
                request.executionShared(), initiating,
                report.map(AiReportDtos.Report::reportId).orElse(null), caseId,
                request.detectionResultVersion(), request.status().name(),
                report.map(AiReportDtos.Report::reportSource).orElse(null), source, request.cacheHit(),
                request.requestedAt(), report.map(AiReportDtos.Report::generatedAt).orElse(null),
                failure, "/api/v1/cases/" + caseId + "/ai-reports/current", request.traceId());
    }

    private boolean terminal(AiReportStatus status) {
        return status == AiReportStatus.COMPLETED || status == AiReportStatus.FALLBACK_COMPLETED
                || status == AiReportStatus.FAILED;
    }

    static String fingerprint(UUID caseId, AiReportDtos.CreateRequest body) {
        String reason = body.regenerationReason();
        return sha256(caseId + ":" + body.detectionResultVersion() + ":"
                + (reason == null ? "-1:" : reason.length() + ":" + reason));
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record CreateOutcome(AiReportDtos.RequestStatus response, boolean accepted) { }
}
