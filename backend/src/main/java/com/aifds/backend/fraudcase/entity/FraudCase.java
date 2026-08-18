package com.aifds.backend.fraudcase.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "fraud_case")
public class FraudCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", nullable = false, length = 48)
    private FraudCaseStatus caseStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_disposition", length = 32)
    private FraudCaseFinalDisposition finalDisposition;

    @Column(name = "assignee_ref", length = 128)
    private String assigneeRef;

    @Column(name = "review_started_at")
    private Instant reviewStartedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "concurrency_version", nullable = false)
    private long concurrencyVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_changed_at", nullable = false)
    private Instant lastChangedAt;

    protected FraudCase() {
    }

    private FraudCase(UUID caseId, Instant createdAt) {
        this.caseId = requireUuidV4(caseId);
        this.caseStatus = FraudCaseStatus.OPEN;
        this.createdAt = requireMicrosecondInstant(createdAt, "createdAt");
        this.lastChangedAt = this.createdAt;
    }

    public static FraudCase open(UUID caseId, Instant createdAt) {
        return new FraudCase(caseId, createdAt);
    }

    public boolean isActive() {
        return caseStatus != null && caseStatus.isActive();
    }

    @PostLoad
    @PrePersist
    @PreUpdate
    private void validateInvariants() {
        requireUuidV4(caseId);
        Objects.requireNonNull(caseStatus, "caseStatus must not be null");
        requireMicrosecondInstant(createdAt, "createdAt");
        requireMicrosecondInstant(lastChangedAt, "lastChangedAt");
        if (lastChangedAt.isBefore(createdAt)) {
            throw new IllegalStateException(
                    "lastChangedAt must not be before createdAt"
            );
        }
        if (concurrencyVersion < 0) {
            throw new IllegalStateException(
                    "concurrencyVersion must not be negative"
            );
        }
        validateOptionalAssignee();
        validateOptionalTimestamp(reviewStartedAt, "reviewStartedAt");
        validateOptionalTimestamp(closedAt, "closedAt");
        validateStateFields();
    }

    private void validateOptionalAssignee() {
        if (assigneeRef != null
                && (assigneeRef.isBlank()
                || assigneeRef.length() > 128
                || !assigneeRef.equals(assigneeRef.trim()))) {
            throw new IllegalStateException(
                    "assigneeRef must be 1 to 128 trimmed characters"
            );
        }
    }

    private void validateOptionalTimestamp(Instant value, String fieldName) {
        if (value == null) {
            return;
        }
        requireMicrosecondInstant(value, fieldName);
        if (value.isBefore(createdAt)) {
            throw new IllegalStateException(
                    fieldName + " must not be before createdAt"
            );
        }
    }

    private void validateStateFields() {
        if (caseStatus.isActive()) {
            if (finalDisposition != null || closedAt != null) {
                throw new IllegalStateException(
                        "Active cases must not have closure fields"
                );
            }
        } else if (finalDisposition == null || closedAt == null) {
            throw new IllegalStateException(
                    "Closed cases must have closure fields"
            );
        }
        if (caseStatus == FraudCaseStatus.IN_REVIEW
                && assigneeRef == null) {
            throw new IllegalStateException(
                    "In-review cases must have an assignee"
            );
        }
    }

    private static UUID requireUuidV4(UUID value) {
        UUID validated = Objects.requireNonNull(
                value,
                "caseId must not be null"
        );
        if (validated.version() != 4 || validated.variant() != 2) {
            throw new IllegalArgumentException("caseId must be a UUID v4");
        }
        return validated;
    }

    private static Instant requireMicrosecondInstant(
            Instant value,
            String fieldName
    ) {
        Instant validated = Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );
        if (validated.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    fieldName + " must have microsecond precision"
            );
        }
        return validated;
    }

    public Long getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public FraudCaseStatus getCaseStatus() {
        return caseStatus;
    }

    public FraudCaseFinalDisposition getFinalDisposition() {
        return finalDisposition;
    }

    public String getAssigneeRef() {
        return assigneeRef;
    }

    public Instant getReviewStartedAt() {
        return reviewStartedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getConcurrencyVersion() {
        return concurrencyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastChangedAt() {
        return lastChangedAt;
    }
}
