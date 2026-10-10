package com.aifds.backend.detection.entity;

import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.aifds.backend.rule.client.dto.ExternalRiskSnapshotRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "detection_evidence")
public class DetectionEvidence {

    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "evidence_id", nullable = false, updatable = false)
    private UUID evidenceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "detection_result_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_detection_evidence_result"
            )
    )
    private DetectionResult detectionResult;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "evidence_type",
            nullable = false,
            length = 32,
            updatable = false
    )
    private DetectionEvidenceType evidenceType;

    @Column(
            name = "reason_code",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String reasonCode;

    @Column(
            name = "display_description",
            nullable = false,
            length = 512,
            updatable = false
    )
    private String displayDescription;

    @Column(name = "score_contribution", updatable = false)
    private Integer scoreContribution;

    @Column(name = "rule_code", length = 64, updatable = false)
    private String ruleCode;

    @Column(name = "rule_version", length = 32, updatable = false)
    private String ruleVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "rule_version_id",
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_detection_evidence_rule_version"
            )
    )
    private RuleVersion ruleVersionRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "observation_summary",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode observationSummary;

    @Column(
            name = "evidence_occurred_at",
            nullable = false,
            updatable = false
    )
    private Instant evidenceOccurredAt;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DetectionEvidence() {
    }

    private DetectionEvidence(
            DetectionResult detectionResult,
            String displayDescription,
            RuleVersion ruleVersionRef,
            RuleEvidenceObservationSummary observationSummary,
            Instant evidenceOccurredAt,
            int sortOrder
    ) {
        this.evidenceId = UUID.randomUUID();
        this.detectionResult = Objects.requireNonNull(
                detectionResult,
                "detectionResult must not be null"
        );
        this.detectionResult.validateEvaluationCutoffConsistency();
        this.evidenceType = DetectionEvidenceType.RULE;
        this.ruleVersionRef = requirePublishedRuleVersion(ruleVersionRef);
        this.reasonCode = requireCode(
                ruleVersionRef.getReasonCode(),
                "reasonCode"
        );
        this.displayDescription = requireDescription(displayDescription);
        this.scoreContribution = ruleVersionRef.getWeight();
        this.ruleCode = requireCode(
                ruleVersionRef.getFraudRule().getRuleCode(),
                "ruleCode"
        );
        this.ruleVersion = Integer.toString(
                ruleVersionRef.getVersionNumber()
        );
        this.observationSummary = Objects.requireNonNull(
                observationSummary,
                "observationSummary must not be null"
        ).toJson();
        this.evidenceOccurredAt = Objects.requireNonNull(
                evidenceOccurredAt,
                "evidenceOccurredAt must not be null"
        );
        if (sortOrder < 0) {
            throw new IllegalArgumentException(
                    "sortOrder must not be negative"
            );
        }
        this.sortOrder = sortOrder;
    }

    public static DetectionEvidence rule(
            DetectionResult detectionResult,
            String displayDescription,
            RuleVersion ruleVersion,
            RuleEvidenceObservationSummary observationSummary,
            Instant evidenceOccurredAt,
            int sortOrder
    ) {
        return new DetectionEvidence(
                detectionResult,
                displayDescription,
                ruleVersion,
                observationSummary,
                evidenceOccurredAt,
                sortOrder
        );
    }

    public static DetectionEvidence ml(DetectionResult result, String reasonCode,
                                       int contribution, int probabilityBasisPoints,
                                       Instant cutoff, int sortOrder) {
        DetectionEvidence evidence = new DetectionEvidence();
        evidence.evidenceId = UUID.randomUUID();
        evidence.detectionResult = Objects.requireNonNull(result);
        result.validateEvaluationCutoffConsistency();
        evidence.evidenceType = DetectionEvidenceType.ML;
        evidence.reasonCode = evidence.requireCode(reasonCode, "reasonCode");
        evidence.displayDescription = "Local synthetic-baseline ML signal";
        if (contribution < 0 || contribution > 40 || probabilityBasisPoints < 0
                || probabilityBasisPoints > 10000 || sortOrder < 0) {
            throw new IllegalArgumentException("Invalid ML evidence values");
        }
        evidence.scoreContribution = contribution;
        evidence.observationSummary = JsonNodeFactory.instance.objectNode()
                .put("probabilityBasisPoints", probabilityBasisPoints);
        evidence.evidenceOccurredAt = Objects.requireNonNull(cutoff);
        evidence.sortOrder = sortOrder;
        return evidence;
    }

    public static DetectionEvidence externalRisk(
            DetectionResult result, ExternalRiskSnapshotRequest snapshot,
            int sortOrder) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (sortOrder < 0
                || snapshot.providerAsOf().isAfter(result.getEvaluationCutoffAt())
                || snapshot.lookedUpAt().isBefore(result.getEvaluationCutoffAt())) {
            throw new IllegalArgumentException("External Risk Evidence time is invalid");
        }
        boolean recipientMatch = snapshot.matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT
                && match.riskType() == ExternalRiskType.SUSPICIOUS_ACCOUNT
                && match.reasonCode() == ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT);
        DetectionEvidence evidence = new DetectionEvidence();
        evidence.evidenceId = UUID.randomUUID();
        evidence.detectionResult = Objects.requireNonNull(result);
        evidence.evidenceType = DetectionEvidenceType.EXTERNAL_RISK;
        evidence.reasonCode = "EXTERNAL_RISK_LOOKUP_SUCCEEDED";
        evidence.displayDescription = "Validated External Risk recipient snapshot";
        evidence.observationSummary = JsonNodeFactory.instance.objectNode()
                .put("sourceVersion", "SCN003-contract-v1")
                .put("providerCode", snapshot.providerCode())
                .put("providerAsOf", snapshot.providerAsOf().toString())
                .put("lookedUpAt", snapshot.lookedUpAt().toString())
                .put("recipientAccountMatched", recipientMatch);
        evidence.evidenceOccurredAt = snapshot.providerAsOf();
        evidence.sortOrder = sortOrder;
        return evidence;
    }

    public static DetectionEvidence recipientHistory(
            DetectionResult result, boolean observed, int sortOrder) {
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative");
        }
        DetectionEvidence evidence = new DetectionEvidence();
        evidence.evidenceId = UUID.randomUUID();
        evidence.detectionResult = Objects.requireNonNull(result);
        evidence.evidenceType = DetectionEvidenceType.BEHAVIOR_PATTERN;
        evidence.reasonCode = observed
                ? "PRIOR_APPROVED_RECIPIENT_TRANSFER_OBSERVED"
                : "NO_ELIGIBLE_PRIOR_RECIPIENT_TRANSFER_OBSERVED";
        evidence.displayDescription = observed
                ? "Eligible prior approved recipient transfer observed"
                : "No eligible prior approved recipient transfer observed";
        evidence.observationSummary = JsonNodeFactory.instance.objectNode()
                .put("sourceVersion", "SCN003-contract-v1")
                .put("priorApprovedRecipientTransferObserved", observed);
        evidence.evidenceOccurredAt = result.getEvaluationCutoffAt();
        evidence.sortOrder = sortOrder;
        return evidence;
    }

    private String requireCode(String value, String fieldName) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be an uppercase code"
            );
        }
        return value;
    }

    private String requireDescription(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 512
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "displayDescription must be 1 to 512 trimmed characters"
            );
        }
        return value;
    }

    private RuleVersion requirePublishedRuleVersion(RuleVersion value) {
        Objects.requireNonNull(value, "ruleVersion must not be null");
        if (value.getStatus() != RuleVersionStatus.PUBLISHED) {
            throw new IllegalArgumentException(
                    "Rule evidence requires a published rule version"
            );
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public UUID getEvidenceId() {
        return evidenceId;
    }

    public DetectionResult getDetectionResult() {
        return detectionResult;
    }

    public DetectionEvidenceType getEvidenceType() {
        return evidenceType;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getDisplayDescription() {
        return displayDescription;
    }

    public Integer getScoreContribution() {
        return scoreContribution;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public RuleVersion getRuleVersionRef() {
        return ruleVersionRef;
    }

    public JsonNode getObservationSummary() {
        return observationSummary.deepCopy();
    }

    public Instant getEvidenceOccurredAt() {
        return evidenceOccurredAt;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
