package com.aifds.backend.fraudcase.command;

import java.util.UUID;

public final class FraudCaseNoteCommand {

    private FraudCaseNoteCommand() {
    }

    public record Create(UUID caseId, String content, long expectedVersion) {
    }

    public record ListQuery(UUID caseId, int page, int size, Direction direction) {
    }

    public enum Direction {
        ASC,
        DESC
    }
}
