package com.aifds.backend.rule.entity;

import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "rule_version")
public class RuleVersion {

    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "rule_version_id", nullable = false, updatable = false)
    private UUID ruleVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "fraud_rule_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_rule_version_fraud_rule")
    )
    private FraudRule fraudRule;

    @Column(
            name = "version_number",
            nullable = false,
            updatable = false
    )
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RuleVersionStatus status;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "weight", nullable = false)
    private int weight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "condition_definition",
            nullable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode conditionDefinition;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    @Column(name = "concurrency_version", nullable = false)
    private long concurrencyVersion;

    protected RuleVersion() {
    }

    private RuleVersion(
            FraudRule fraudRule,
            int versionNumber,
            String reasonCode,
            int weight,
            JsonNode conditionDefinition,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        this.ruleVersionId = UUID.randomUUID();
        this.fraudRule = Objects.requireNonNull(
                fraudRule,
                "fraudRule must not be null"
        );
        if (versionNumber < 1) {
            throw new IllegalArgumentException(
                    "versionNumber must be positive"
            );
        }
        this.versionNumber = versionNumber;
        this.status = RuleVersionStatus.DRAFT;
        applyDraftDefinition(
                reasonCode,
                weight,
                conditionDefinition,
                effectiveFrom,
                effectiveTo
        );
    }

    public static RuleVersion draft(
            FraudRule fraudRule,
            int versionNumber,
            String reasonCode,
            int weight,
            JsonNode conditionDefinition,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        return new RuleVersion(
                fraudRule,
                versionNumber,
                reasonCode,
                weight,
                conditionDefinition,
                effectiveFrom,
                effectiveTo
        );
    }

    public void updateDraft(
            String updatedReasonCode,
            int updatedWeight,
            JsonNode updatedConditionDefinition,
            Instant updatedEffectiveFrom,
            Instant updatedEffectiveTo
    ) {
        requireStatus(RuleVersionStatus.DRAFT);
        applyDraftDefinition(
                updatedReasonCode,
                updatedWeight,
                updatedConditionDefinition,
                updatedEffectiveFrom,
                updatedEffectiveTo
        );
    }

    public void publish(Instant publicationTime) {
        requireStatus(RuleVersionStatus.DRAFT);
        if (fraudRule.getLifecycleStatus()
                != FraudRuleLifecycleStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active fraud rules can publish a version"
            );
        }
        if (effectiveFrom == null) {
            throw new IllegalStateException(
                    "Published rule versions require effectiveFrom"
            );
        }
        this.publishedAt = Objects.requireNonNull(
                publicationTime,
                "publishedAt must not be null"
        );
        this.status = RuleVersionStatus.PUBLISHED;
    }

    public void withdraw() {
        requireStatus(RuleVersionStatus.DRAFT);
        this.status = RuleVersionStatus.WITHDRAWN;
    }

    public void closeEffectivePeriod(Instant closingTime) {
        requireStatus(RuleVersionStatus.PUBLISHED);
        if (effectiveTo != null) {
            throw new IllegalStateException(
                    "effectiveTo can only be set once"
            );
        }
        Instant validatedClosingTime = Objects.requireNonNull(
                closingTime,
                "effectiveTo must not be null"
        );
        if (!validatedClosingTime.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "effectiveTo must be after effectiveFrom"
            );
        }
        this.effectiveTo = validatedClosingTime;
    }

    private void applyDraftDefinition(
            String newReasonCode,
            int newWeight,
            JsonNode newConditionDefinition,
            Instant newEffectiveFrom,
            Instant newEffectiveTo
    ) {
        String validatedReasonCode = requireReasonCode(newReasonCode);
        RuleV1ContractRegistry.requireCompatible(
                fraudRule.getRuleCode(),
                validatedReasonCode
        );
        this.reasonCode = validatedReasonCode;
        this.weight = requireWeight(newWeight);
        this.conditionDefinition = RuleConditionDefinition.from(
                fraudRule.getRuleCode(),
                newConditionDefinition
        ).toJson();
        validateEffectivePeriod(newEffectiveFrom, newEffectiveTo);
        this.effectiveFrom = newEffectiveFrom;
        this.effectiveTo = newEffectiveTo;
    }

    private static String requireReasonCode(String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be an uppercase snake case code"
            );
        }
        if (!RuleV1ContractRegistry.supportsReasonCode(value)) {
            throw new IllegalArgumentException(
                    "reasonCode is not supported by Rule v1 evidence"
            );
        }
        return value;
    }

    private static int requireWeight(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(
                    "weight must be between 1 and 100"
            );
        }
        return value;
    }

    private static void validateEffectivePeriod(
            Instant start,
            Instant end
    ) {
        if (end != null && (start == null || !end.isAfter(start))) {
            throw new IllegalArgumentException(
                    "effectiveTo must be after effectiveFrom"
            );
        }
    }

    private void requireStatus(RuleVersionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Rule version status must be " + expected
            );
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getRuleVersionId() {
        return ruleVersionId;
    }

    public FraudRule getFraudRule() {
        return fraudRule;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public RuleVersionStatus getStatus() {
        return status;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public int getWeight() {
        return weight;
    }

    public JsonNode getConditionDefinition() {
        return conditionDefinition.deepCopy();
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public long getConcurrencyVersion() {
        return concurrencyVersion;
    }
}
