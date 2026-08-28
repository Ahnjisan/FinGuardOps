package com.aifds.recovery.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecoveryCommandArgumentsTest {

    private static final String PREFIX =
            IdempotencyRecoveryCommandArguments.PREFIX;

    @Test
    void recoveryModeIsInactiveWithoutExactPrefix() {
        assertThat(IdempotencyRecoveryCommandArguments.hasRecoveryPrefix(
                new String[0]
        )).isFalse();
        assertThat(IdempotencyRecoveryCommandArguments.hasRecoveryPrefix(
                new String[]{"--server.port=0"}
        )).isFalse();
        assertThat(IdempotencyRecoveryCommandArguments.hasRecoveryPrefix(
                new String[]{"x" + PREFIX + "enabled=true"}
        )).isFalse();
    }

    @Test
    void recoveryModeActivatesWhenAnyExactPrefixArgumentExists() {
        assertThat(IdempotencyRecoveryCommandArguments.hasRecoveryPrefix(
                new String[]{"--server.port=0", PREFIX + "enabled=true"}
        )).isTrue();
    }

    @Test
    void parsesInspectDefaults() {
        IdempotencyRecoveryCommandArguments arguments = parse(
                "enabled=true",
                "action=inspect"
        );

        assertThat(arguments.action())
                .isEqualTo(IdempotencyRecoveryCommandArguments.Action.INSPECT);
        assertThat(arguments.threshold()).isEqualTo(Duration.ofMinutes(30));
        assertThat(arguments.pageSize()).isEqualTo(50);
        assertThat(arguments.recordId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT5M", "P7D"})
    void acceptsInclusiveThresholdBoundaries(String threshold) {
        assertThat(parse(
                "enabled=true",
                "action=inspect",
                "threshold=" + threshold
        ).threshold()).isEqualTo(Duration.parse(threshold));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT4M59S", "P7DT0.000000001S", "invalid"})
    void rejectsInvalidThresholds(String threshold) {
        assertInvalid(
                "enabled=true",
                "action=inspect",
                "threshold=" + threshold
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void acceptsInclusivePageSizeBoundaries(String pageSize) {
        assertThat(parse(
                "enabled=true",
                "action=inspect",
                "page-size=" + pageSize
        ).pageSize()).isEqualTo(Integer.parseInt(pageSize));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "+1", "1.0"})
    void rejectsInvalidPageSizes(String pageSize) {
        assertInvalid(
                "enabled=true",
                "action=inspect",
                "page-size=" + pageSize
        );
    }

    @Test
    void parsesRecoverCanonicalPositiveLong() {
        IdempotencyRecoveryCommandArguments arguments = parse(
                "enabled=true",
                "action=recover",
                "record-id=9223372036854775807"
        );

        assertThat(arguments.action())
                .isEqualTo(IdempotencyRecoveryCommandArguments.Action.RECOVER);
        assertThat(arguments.recordId()).isEqualTo(Long.MAX_VALUE);
        assertThat(arguments.threshold()).isNull();
        assertThat(arguments.pageSize()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0", "01", "+1", "-1", " 1", "1 ", "1.0", "1e3",
            "9223372036854775808"
    })
    void rejectsNonCanonicalOrOverflowRecordIds(String recordId) {
        assertInvalid(
                "enabled=true",
                "action=recover",
                "record-id=" + recordId
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "enabled=false",
            "enabled=TRUE",
            "action=INSPECT",
            "action=Recover"
    })
    void rejectsNonExactActivationAndActionValues(String replacement) {
        String[] options = replacement.startsWith("enabled")
                ? new String[]{replacement, "action=inspect"}
                : new String[]{"enabled=true", replacement};
        assertInvalid(options);
    }

    @Test
    void rejectsMissingDuplicateUnknownPositionalAndEmptyOptions() {
        assertInvalid("action=inspect");
        assertInvalid("enabled=true");
        assertInvalid("enabled=true", "action=inspect", "action=inspect");
        assertInvalid("enabled=true", "action=inspect", "unknown=value");
        assertInvalid("enabled=true", "action=inspect", "actor-type=SYSTEM");
        assertInvalid("enabled=true", "action=inspect", "actor-reference=x");
        assertInvalid("enabled=true", "action=inspect", "--server.port=0");
        assertInvalid("enabled=true", "action");
        assertInvalid("enabled=true", "action=");
        assertInvalid("enabled=true", "=inspect");
    }

    @Test
    void rejectsMixedActionSpecificOptions() {
        assertInvalid(
                "enabled=true",
                "action=inspect",
                "record-id=1"
        );
        assertInvalid(
                "enabled=true",
                "action=recover",
                "record-id=1",
                "threshold=PT30M"
        );
        assertInvalid(
                "enabled=true",
                "action=recover",
                "record-id=1",
                "page-size=50"
        );
        assertInvalid("enabled=true", "action=recover");
    }

    private IdempotencyRecoveryCommandArguments parse(String... options) {
        return IdempotencyRecoveryCommandArguments.parse(prefixed(options));
    }

    private void assertInvalid(String... options) {
        assertThatThrownBy(() -> IdempotencyRecoveryCommandArguments.parse(
                prefixed(options)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private String[] prefixed(String... options) {
        String[] arguments = new String[options.length];
        for (int index = 0; index < options.length; index++) {
            String option = options[index];
            arguments[index] = option.startsWith("--")
                    ? option
                    : PREFIX + option;
        }
        return arguments;
    }
}
