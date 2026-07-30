package com.aifds.backend.detection.entity;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "detection_result")
public class DetectionResult {

    private static final Pattern FAILURE_CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(
            name = "detection_result_id",
            nullable = false,
            updatable = false
    )
    private UUID detectionResultId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "financial_transaction_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_detection_result_transaction"
            )
    )
    private FinancialTransaction financialTransaction;

    @Column(
            name = "detection_result_version",
            nullable = false,
            updatable = false
    )
    private int detectionResultVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 16)
    private DetectionAnalysisStatus analysisStatus;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel;

    @Column(
            name = "rule_set_version",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String ruleSetVersion;

    @Column(
            name = "scoring_policy_version",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String scoringPolicyVersion;

    @Column(
            name = "feature_version",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String featureVersion;

    @Column(name = "model_version", length = 64, updatable = false)
    private String modelVersion;

    @Column(
            name = "evaluation_cutoff_at",
            nullable = false,
            updatable = false
    )
    private Instant evaluationCutoffAt;

    @Column(name = "analysis_started_at")
    private Instant analysisStartedAt;

    @Column(name = "analysis_completed_at")
    private Instant analysisCompletedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(
            name = "analysis_trace_id",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String analysisTraceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DetectionResult() {
    }

    private DetectionResult(
            FinancialTransaction financialTransaction,
            int detectionResultVersion,
            String ruleSetVersion,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            Instant evaluationCutoffAt,
            String analysisTraceId
    ) {
        this.detectionResultId = UUID.randomUUID();
        this.financialTransaction = Objects.requireNonNull(
                financialTransaction,
                "financialTransaction must not be null"
        );
        if (detectionResultVersion < 1) {
            throw new IllegalArgumentException(
                    "detectionResultVersion must be positive"
            );
        }
        this.detectionResultVersion = detectionResultVersion;
        this.ruleSetVersion = requireVersion(
                ruleSetVersion,
                "ruleSetVersion"
        );
        this.scoringPolicyVersion = requireVersion(
                scoringPolicyVersion,
                "scoringPolicyVersion"
        );
        this.featureVersion = requireVersion(
                featureVersion,
                "featureVersion"
        );
        this.modelVersion = modelVersion == null
                ? null
                : requireVersion(modelVersion, "modelVersion");
        this.evaluationCutoffAt = Objects.requireNonNull(
                evaluationCutoffAt,
                "evaluationCutoffAt must not be null"
        );
        this.analysisTraceId = requireTraceId(analysisTraceId);
        this.analysisStatus = DetectionAnalysisStatus.PENDING;
    }

    public static DetectionResult pending(
            FinancialTransaction financialTransaction,
            int detectionResultVersion,
            String ruleSetVersion,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            Instant evaluationCutoffAt,
            String analysisTraceId
    ) {
        return new DetectionResult(
                financialTransaction,
                detectionResultVersion,
                ruleSetVersion,
                scoringPolicyVersion,
                featureVersion,
                modelVersion,
                evaluationCutoffAt,
                analysisTraceId
        );
    }

    public void start(Instant startedAt) {
        requireStatus(DetectionAnalysisStatus.PENDING);
        this.analysisStartedAt = Objects.requireNonNull(
                startedAt,
                "startedAt must not be null"
        );
        this.analysisStatus = DetectionAnalysisStatus.IN_PROGRESS;
        this.updatedAt = startedAt;
    }

    public void complete(
            int completedRiskScore,
            RiskLevel completedRiskLevel,
            Instant completedAt
    ) {
        requireStatus(DetectionAnalysisStatus.IN_PROGRESS);
        if (completedRiskScore < 0 || completedRiskScore > 100) {
            throw new IllegalArgumentException(
                    "riskScore must be between 0 and 100"
            );
        }
        Objects.requireNonNull(
                completedRiskLevel,
                "riskLevel must not be null"
        );
        Instant validatedCompletedAt = Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );
        if (validatedCompletedAt.isBefore(analysisStartedAt)) {
            throw new IllegalArgumentException(
                    "completedAt must not be before analysisStartedAt"
            );
        }

        this.riskScore = completedRiskScore;
        this.riskLevel = completedRiskLevel;
        this.analysisCompletedAt = validatedCompletedAt;
        this.failureCode = null;
        this.analysisStatus = DetectionAnalysisStatus.COMPLETED;
        this.updatedAt = validatedCompletedAt;
    }

    public void fail(String failedCode, Instant failedAt) {
        if (analysisStatus != DetectionAnalysisStatus.PENDING
                && analysisStatus != DetectionAnalysisStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Only non-terminal detection results can fail"
            );
        }
        if (failedCode == null
                || !FAILURE_CODE_PATTERN.matcher(failedCode).matches()) {
            throw new IllegalArgumentException(
                    "failureCode must match ^[A-Z][A-Z0-9_]{0,63}$"
            );
        }
        Instant validatedFailedAt = Objects.requireNonNull(
                failedAt,
                "failedAt must not be null"
        );
        if (analysisStartedAt != null
                && validatedFailedAt.isBefore(analysisStartedAt)) {
            throw new IllegalArgumentException(
                    "failedAt must not be before analysisStartedAt"
            );
        }

        this.riskScore = null;
        this.riskLevel = null;
        this.analysisCompletedAt = validatedFailedAt;
        this.failureCode = failedCode;
        this.analysisStatus = DetectionAnalysisStatus.FAILED;
        this.updatedAt = validatedFailedAt;
    }

    public boolean belongsTo(FinancialTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        if (financialTransaction == transaction) {
            return true;
        }
        return financialTransaction.getTransactionId() != null
                && financialTransaction.getTransactionId().equals(
                transaction.getTransactionId()
        );
    }

    @PrePersist
    private void initializeTimestamps() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    private void requireStatus(DetectionAnalysisStatus expected) {
        if (analysisStatus != expected) {
            throw new IllegalStateException(
                    "Detection result status must be " + expected
            );
        }
    }

    private String requireVersion(String value, String fieldName) {
        if (value == null
                || value.isBlank()
                || value.length() > 64
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1 to 64 trimmed characters"
            );
        }
        return value;
    }

    private String requireTraceId(String value) {
        if (value == null
                || value.length() < 8
                || value.length() > 64
                || !value.matches(
                "^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$"
        )) {
            throw new IllegalArgumentException(
                    "analysisTraceId has an invalid format"
            );
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public UUID getDetectionResultId() {
        return detectionResultId;
    }

    public FinancialTransaction getFinancialTransaction() {
        return financialTransaction;
    }

    public int getDetectionResultVersion() {
        return detectionResultVersion;
    }

    public DetectionAnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getRuleSetVersion() {
        return ruleSetVersion;
    }

    public String getScoringPolicyVersion() {
        return scoringPolicyVersion;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Instant getEvaluationCutoffAt() {
        return evaluationCutoffAt;
    }

    public Instant getAnalysisStartedAt() {
        return analysisStartedAt;
    }

    public Instant getAnalysisCompletedAt() {
        return analysisCompletedAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getAnalysisTraceId() {
        return analysisTraceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
