package com.aifds.recovery.idempotency;

import java.util.List;

public record IdempotencyRecoveryCommandResult(
        int exitCode,
        List<String> standardOutputLines
) {

    public IdempotencyRecoveryCommandResult {
        if (exitCode != 0 && exitCode != 3) {
            throw new IllegalArgumentException(
                    "typed command result exitCode must be 0 or 3"
            );
        }
        standardOutputLines = List.copyOf(standardOutputLines);
    }
}
