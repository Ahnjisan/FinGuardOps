package com.aifds.backend.detection.entity;

import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.entity.RuleVersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @PrePersist
    private void initializeCreatedAt() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
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
