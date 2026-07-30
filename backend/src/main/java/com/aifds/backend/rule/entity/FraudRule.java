package com.aifds.backend.rule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "fraud_rule")
public class FraudRule {

    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "fraud_rule_id", nullable = false, updatable = false)
    private UUID fraudRuleId;

    @Column(
            name = "rule_code",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String ruleCode;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 16)
    private FraudRuleLifecycleStatus lifecycleStatus;

    @Version
    @Column(name = "concurrency_version", nullable = false)
    private long concurrencyVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FraudRule() {
    }

    private FraudRule(String ruleCode, String name, String description) {
        this.fraudRuleId = UUID.randomUUID();
        this.ruleCode = requireCode(ruleCode);
        this.name = requireText(name, "name", 128);
        this.description = requireText(description, "description", 512);
        this.lifecycleStatus = FraudRuleLifecycleStatus.ACTIVE;
    }

    public static FraudRule create(
            String ruleCode,
            String name,
            String description
    ) {
        return new FraudRule(ruleCode, name, description);
    }

    public void updateDetails(String updatedName, String updatedDescription) {
        this.name = requireText(updatedName, "name", 128);
        this.description = requireText(
                updatedDescription,
                "description",
                512
        );
    }

    public void retire() {
        if (lifecycleStatus != FraudRuleLifecycleStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active fraud rules can be retired"
            );
        }
        this.lifecycleStatus = FraudRuleLifecycleStatus.RETIRED;
    }

    @PrePersist
    private void initializeTimestamps() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireCode(String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ruleCode must be an uppercase snake case code"
            );
        }
        return value;
    }

    private static String requireText(
            String value,
            String fieldName,
            int maximumLength
    ) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1 to " + maximumLength
                            + " trimmed characters"
            );
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public UUID getFraudRuleId() {
        return fraudRuleId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public FraudRuleLifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public long getConcurrencyVersion() {
        return concurrencyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
