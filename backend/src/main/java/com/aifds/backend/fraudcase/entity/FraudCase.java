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
import java.util.regex.Pattern;

@Entity
@Table(name = "fraud_case")
public class FraudCase {

    private static final Pattern WORKFLOW_ASSIGNEE_REF_PATTERN =
            Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}"
                            + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
            );

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

    public void startReview(String newAssigneeRef, Instant changedAt) {
        requireNotClosed();
        if (caseStatus != FraudCaseStatus.OPEN) {
            throw new IllegalStateException(
                    "Review can only start from an open case"
            );
        }
        assigneeRef = requireWorkflowAssigneeRef(newAssigneeRef);
        caseStatus = FraudCaseStatus.IN_REVIEW;
        Instant workflowTime = requireWorkflowTime(changedAt);
        if (reviewStartedAt == null) {
            reviewStartedAt = workflowTime;
        }
        lastChangedAt = workflowTime;
        validateInvariants();
    }

    public void requestAdditionalInformation(Instant changedAt) {
        requireNotClosed();
        if (caseStatus != FraudCaseStatus.IN_REVIEW) {
            throw new IllegalStateException(
                    "Additional information can only be requested in review"
            );
        }
        caseStatus = FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED;
        lastChangedAt = requireWorkflowTime(changedAt);
        validateInvariants();
    }

    public void resumeReview(Instant changedAt) {
        requireNotClosed();
        if (caseStatus
                != FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED) {
            throw new IllegalStateException(
                    "Review can only resume after additional information"
            );
        }
        if (assigneeRef == null) {
            throw new IllegalStateException(
                    "Review cannot resume without an assignee"
            );
        }
        caseStatus = FraudCaseStatus.IN_REVIEW;
        lastChangedAt = requireWorkflowTime(changedAt);
        validateInvariants();
    }

    public void changeAssignee(String newAssigneeRef, Instant changedAt) {
        requireNotClosed();
        if (caseStatus == FraudCaseStatus.OPEN) {
            throw new IllegalStateException(
                    "Open case assignee changes require review start"
            );
        }
        if (newAssigneeRef == null) {
            if (caseStatus == FraudCaseStatus.IN_REVIEW) {
                throw new IllegalStateException(
                        "In-review cases cannot release the assignee"
                );
            }
        } else {
            requireWorkflowAssigneeRef(newAssigneeRef);
        }
        if (Objects.equals(assigneeRef, newAssigneeRef)) {
            throw new IllegalStateException("Assignee must change");
        }
        assigneeRef = newAssigneeRef;
        lastChangedAt = requireWorkflowTime(changedAt);
        validateInvariants();
    }

    public void resolve(
            FraudCaseFinalDisposition disposition,
            Instant resolutionTime
    ) {
        requireNotClosed();
        if (caseStatus != FraudCaseStatus.IN_REVIEW) {
            throw new IllegalStateException(
                    "Only in-review cases can be resolved"
            );
        }
        if (assigneeRef == null || reviewStartedAt == null) {
            throw new IllegalStateException(
                    "In-review case resolution data is inconsistent"
            );
        }
        FraudCaseFinalDisposition validatedDisposition =
                Objects.requireNonNull(
                disposition,
                "finalDisposition must not be null"
        );
        Instant resolvedAt = requireWorkflowTime(resolutionTime);
        finalDisposition = validatedDisposition;
        caseStatus = FraudCaseStatus.CLOSED;
        closedAt = resolvedAt;
        lastChangedAt = resolvedAt;
        validateInvariants();
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

    private void requireNotClosed() {
        if (caseStatus == FraudCaseStatus.CLOSED) {
            throw new IllegalStateException("Closed cases cannot be changed");
        }
    }

    private Instant requireWorkflowTime(Instant value) {
        Instant validated = requireMicrosecondInstant(value, "changedAt");
        if (validated.isBefore(lastChangedAt)) {
            throw new IllegalArgumentException(
                    "changedAt must not be before lastChangedAt"
            );
        }
        return validated;
    }

    private String requireWorkflowAssigneeRef(String value) {
        if (value == null
                || value.length() != 36
                || !WORKFLOW_ASSIGNEE_REF_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "assigneeRef must be a canonical lowercase UUID v4"
            );
        }
        return value;
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
