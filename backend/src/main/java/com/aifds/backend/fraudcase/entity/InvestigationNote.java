package com.aifds.backend.fraudcase.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Immutable
@Table(name = "investigation_note")
public class InvestigationNote {

    public static final String SYSTEM_AUTHOR_REF = "finguardops-backend";
    private static final Pattern USER_AUTHOR_REF_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "note_id", nullable = false, updatable = false)
    private UUID noteId;

    @Column(name = "fraud_case_id", nullable = false, updatable = false)
    private Long fraudCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 16, updatable = false)
    private InvestigationNoteAuthorType authorType;

    @Column(name = "author_ref", nullable = false, length = 128, updatable = false)
    private String authorRef;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InvestigationNote() {
    }

    private InvestigationNote(
            UUID noteId,
            Long fraudCaseId,
            InvestigationNoteAuthorType authorType,
            String authorRef,
            String content,
            Instant createdAt
    ) {
        this.noteId = noteId;
        this.fraudCaseId = fraudCaseId;
        this.authorType = authorType;
        this.authorRef = authorRef;
        this.content = content;
        this.createdAt = createdAt;
        validateInvariants();
    }

    public static InvestigationNote systemAuthored(
            UUID noteId,
            Long fraudCaseId,
            String content,
            Instant createdAt
    ) {
        return new InvestigationNote(
                noteId,
                fraudCaseId,
                InvestigationNoteAuthorType.SYSTEM,
                SYSTEM_AUTHOR_REF,
                content,
                createdAt
        );
    }

    public static InvestigationNote userAuthored(
            UUID noteId,
            Long fraudCaseId,
            UUID authorSubject,
            String content,
            Instant createdAt
    ) {
        UUID subject = Objects.requireNonNull(
                authorSubject,
                "authorSubject must not be null"
        );
        return new InvestigationNote(
                noteId,
                fraudCaseId,
                InvestigationNoteAuthorType.USER,
                subject.toString(),
                content,
                createdAt
        );
    }

    @PrePersist
    private void validateInvariants() {
        UUID validatedNoteId = Objects.requireNonNull(noteId, "noteId must not be null");
        if (validatedNoteId.version() != 4 || validatedNoteId.variant() != 2) {
            throw new IllegalArgumentException("noteId must be a UUID v4");
        }
        if (fraudCaseId == null || fraudCaseId < 1) {
            throw new IllegalArgumentException("fraudCaseId must be positive");
        }
        if (!isValidAuthor()) {
            throw new IllegalArgumentException("Investigation note actor is not supported");
        }
        Objects.requireNonNull(content, "content must not be null");
        Instant validatedTime = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (validatedTime.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("createdAt must have microsecond precision");
        }
    }

    private boolean isValidAuthor() {
        if (authorType == InvestigationNoteAuthorType.SYSTEM) {
            return SYSTEM_AUTHOR_REF.equals(authorRef);
        }
        return authorType == InvestigationNoteAuthorType.USER
                && authorRef != null
                && USER_AUTHOR_REF_PATTERN.matcher(authorRef).matches()
                && isUuidV4(authorRef);
    }

    private boolean isUuidV4(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.version() == 4
                    && uuid.variant() == 2
                    && uuid.toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @PreUpdate
    private void rejectUpdate() {
        throw new IllegalStateException("InvestigationNote is append-only");
    }

    @PreRemove
    private void rejectRemove() {
        throw new IllegalStateException("InvestigationNote cannot be removed");
    }

    public Long getId() { return id; }
    public UUID getNoteId() { return noteId; }
    public Long getFraudCaseId() { return fraudCaseId; }
    public InvestigationNoteAuthorType getAuthorType() { return authorType; }
    public String getAuthorRef() { return authorRef; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
