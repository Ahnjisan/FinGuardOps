package com.aifds.backend.rule.operation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleV1DefaultRuleSetPublicationDiagnosticBoundaryTest {

    private static final String PREFIX =
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary.WIRE_PREFIX;

    @Test
    void armsOnlyForExactPublicationProfileAndEnabledProperty() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary unarmed = boundary(
                new ArrayList<>()
        );
        assertThat(unarmed.tryArm(new MockEnvironment())).isFalse();
        assertThat(unarmed.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State.UNARMED
        );

        MockEnvironment disabled = publicationEnvironment();
        disabled.setProperty(enabledProperty(), "false");
        assertThat(boundary(new ArrayList<>()).tryArm(disabled)).isFalse();

        MockEnvironment caseVariant = publicationEnvironment();
        caseVariant.setProperty(enabledProperty(), "TRUE");
        assertThat(boundary(new ArrayList<>()).tryArm(caseVariant)).isFalse();

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary armed = boundary(
                new ArrayList<>()
        );
        assertThat(armed.tryArm(publicationEnvironment())).isTrue();
        assertThat(armed.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State
                        .ARMED_PRE_RUN
        );

        MockEnvironment broken = new MockEnvironment() {
            @Override
            public String[] getActiveProfiles() {
                throw new AssertionError("diagnostic-only failure");
            }
        };
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary safe = boundary(
                new ArrayList<>()
        );
        assertThatCode(() -> safe.tryArm(broken)).doesNotThrowAnyException();
        assertThat(safe.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State.UNARMED
        );
    }

    @Test
    void followsTheOnlyValidForwardStateSequence() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed(
                new ArrayList<>()
        );

        boundary.beginRunnerConfiguration();
        assertState(boundary, "RUNNER_CONFIGURATION");
        boundary.beginServiceExecution();
        assertState(boundary, "SERVICE_EXECUTION");
        boundary.publicationCommitted();
        assertState(boundary, "PUBLICATION_COMMITTED");
        boundary.runnerSucceeded();
        assertState(boundary, "RUNNER_SUCCEEDED");
    }

    @Test
    void invalidAndDuplicateTransitionsFailClosed() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary invalid = armed(
                new ArrayList<>()
        );
        assertThatThrownBy(invalid::beginServiceExecution)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rule publication diagnostic state transition failed");

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary duplicate = armed(
                new ArrayList<>()
        );
        duplicate.beginRunnerConfiguration();
        assertThatThrownBy(duplicate::beginRunnerConfiguration)
                .isInstanceOf(IllegalStateException.class);

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary duplicateArm = armed(
                new ArrayList<>()
        );
        assertThat(duplicateArm.tryArm(publicationEnvironment())).isFalse();
        assertThatThrownBy(duplicateArm::beginRunnerConfiguration)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emitsStartupOnlyBeforeRunnerEntry() {
        List<String> startup = new ArrayList<>();
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary before = armed(startup);
        before.emitStartupFailure(new IllegalStateException("raw sentinel"));
        assertThat(startup).containsExactly(
                PREFIX + "RULE_PUBLICATION_BACKEND_STARTUP_FAILED"
        );

        List<String> afterEntry = new ArrayList<>();
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary entered = armed(
                afterEntry
        );
        entered.beginRunnerConfiguration();
        entered.emitStartupFailure(new IllegalStateException("raw sentinel"));
        assertThat(afterEntry).isEmpty();

        List<String> afterCommit = new ArrayList<>();
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary committed = armed(
                afterCommit
        );
        committed.beginRunnerConfiguration();
        committed.beginServiceExecution();
        committed.publicationCommitted();
        committed.emitStartupFailure(new IllegalStateException("raw sentinel"));
        assertThat(afterCommit).isEmpty();
    }

    @Test
    void mapsAllApprovedConfigurationIdentitiesExactly() {
        Map<Throwable, String> cases = Map.ofEntries(
                failure(IllegalStateException.class,
                        "Rule v1 default publication is forbidden in production",
                        "RULE_PUBLICATION_RUNNER_PRODUCTION_PROFILE_REJECTED"),
                failure(IllegalStateException.class,
                        "Rule v1 default publication requires its operation profile and a local, dev, or test profile",
                        "RULE_PUBLICATION_RUNNER_APPROVED_PROFILE_REQUIRED"),
                failure(IllegalStateException.class,
                        "Rule v1 default publication requires spring.main.web-application-type=none",
                        "RULE_PUBLICATION_RUNNER_NON_WEB_MODE_REQUIRED"),
                failure(IllegalStateException.class,
                        "Rule v1 default publication confirmation does not match",
                        "RULE_PUBLICATION_RUNNER_CONFIRMATION_REJECTED"),
                failure(IllegalArgumentException.class,
                        "Rule v1 default effectiveFrom must be a canonical UTC Instant",
                        "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED"),
                failure(IllegalArgumentException.class,
                        "Rule v1 default effectiveFrom must be canonical UTC with at most microsecond precision",
                        "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED"),
                failure(IllegalArgumentException.class,
                        "Rule v1 default effectiveFrom must be in the future",
                        "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_NOT_FUTURE")
        );
        for (Map.Entry<Throwable, String> item : cases.entrySet()) {
            List<String> lines = new ArrayList<>();
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed(
                    lines
            );
            boundary.beginRunnerConfiguration();
            boundary.emitConfigurationFailure(item.getKey());
            assertThat(lines).containsExactly(PREFIX + item.getValue());
        }
    }

    @Test
    void mapsAllApprovedServiceIdentitiesExactly() {
        Map<Throwable, String> cases = Map.ofEntries(
                failure(IllegalStateException.class,
                        "The complete V5 default Rule v1 set does not exist",
                        "RULE_PUBLICATION_SERVICE_DEFAULT_SET_INCOMPLETE"),
                failure(IllegalStateException.class,
                        "Default Rule v1 identity does not match the V5 contract",
                        "RULE_PUBLICATION_SERVICE_IDENTITY_MISMATCH"),
                failure(IllegalStateException.class,
                        "Default Rule v1 FraudRules must be ACTIVE",
                        "RULE_PUBLICATION_SERVICE_FRAUD_RULE_INACTIVE"),
                failure(IllegalStateException.class,
                        "Default Rule v1 versions must be open-ended",
                        "RULE_PUBLICATION_SERVICE_VERSION_PERIOD_INVALID"),
                failure(IllegalStateException.class,
                        "Default Rule v1 versions must be all DRAFT or all PUBLISHED",
                        "RULE_PUBLICATION_SERVICE_VERSION_STATUS_INVALID"),
                failure(IllegalStateException.class,
                        "Default Rule v1 DRAFT period metadata must be unset",
                        "RULE_PUBLICATION_SERVICE_DRAFT_METADATA_INVALID"),
                failure(IllegalArgumentException.class,
                        "effectiveFrom must be later than the publication time",
                        "RULE_PUBLICATION_SERVICE_EFFECTIVE_FROM_EXPIRED"),
                failure(IllegalArgumentException.class,
                        "amountThreshold must be a positive canonical integer string within NUMERIC(19,4) integer range",
                        "RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID")
        );
        for (Map.Entry<Throwable, String> item : cases.entrySet()) {
            List<String> lines = new ArrayList<>();
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary =
                    serviceBoundary(lines);
            boundary.emitServiceFailure(item.getKey());
            assertThat(lines).containsExactly(PREFIX + item.getValue());
        }
    }

    @Test
    void unknownDuplicateAndCrossPhaseIdentitiesUseBroadPhaseCodes() {
        assertConfigurationCode(
                new IllegalStateException("unknown raw sentinel"),
                "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED"
        );
        assertConfigurationCode(
                new IllegalStateException(
                        "Rule v1 default publication confirmation does not match",
                        new IllegalStateException(
                                "Rule v1 default publication confirmation does not match"
                        )
                ),
                "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED"
        );
        assertConfigurationCode(
                new IllegalStateException(
                        "Rule v1 default publication confirmation does not match",
                        new IllegalArgumentException(
                                "Rule v1 default effectiveFrom must be in the future"
                        )
                ),
                "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED"
        );
        assertServiceCode(
                new IllegalStateException(
                        "The complete V5 default Rule v1 set does not exist",
                        new IllegalStateException(
                                "Rule v1 default publication confirmation does not match"
                        )
                ),
                "RULE_PUBLICATION_SERVICE_EXECUTION_FAILED"
        );
    }

    @Test
    void nestedCauseIsRecognizedButSuppressedThrowableIsIgnored() {
        assertConfigurationCode(
                new RuntimeException(
                        "wrapper",
                        new IllegalStateException(
                                "Rule v1 default publication confirmation does not match"
                        )
                ),
                "RULE_PUBLICATION_RUNNER_CONFIRMATION_REJECTED"
        );

        RuntimeException failure = new RuntimeException("wrapper");
        failure.addSuppressed(new IllegalStateException(
                "Rule v1 default publication confirmation does not match"
        ));
        assertConfigurationCode(
                failure,
                "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED"
        );
    }

    @Test
    void causeCycleTerminatesAtBroadCode() {
        class Cycle extends RuntimeException {
            private Throwable next;

            @Override
            public synchronized Throwable getCause() {
                return next;
            }

            void next(Throwable value) {
                next = value;
            }
        }
        Cycle cycle = new Cycle();
        Throwable cyclic = new IllegalStateException(
                "Rule v1 default publication confirmation does not match",
                cycle
        );
        cycle.next(cyclic);
        assertConfigurationCode(
                cyclic,
                "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED"
        );
    }

    @Test
    void markerWriterFailureIsSwallowedAndNeverRetried() {
        int[] attempts = {0};
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary =
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary(line -> {
                    attempts[0]++;
                    throw new IllegalStateException("writer failure");
                });
        assertThat(boundary.tryArm(publicationEnvironment())).isTrue();

        assertThatCode(() -> boundary.emitStartupFailure(
                new IllegalStateException("product failure")
        )).doesNotThrowAnyException();
        boundary.emitStartupFailure(new IllegalStateException("second"));

        assertThat(attempts[0]).isOne();
        assertThat(boundary.markerAttempted()).isTrue();
    }

    @Test
    void concurrentEmissionWritesAtMostOneExactLine() throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed(lines);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boundary.emitStartupFailure(new IllegalStateException("raw"));
            });
            thread.start();
            threads.add(thread);
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(lines).containsExactly(
                PREFIX + "RULE_PUBLICATION_BACKEND_STARTUP_FAILED"
        );
    }

    private void assertConfigurationCode(Throwable failure, String code) {
        List<String> lines = new ArrayList<>();
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed(lines);
        boundary.beginRunnerConfiguration();
        boundary.emitConfigurationFailure(failure);
        assertThat(lines).containsExactly(PREFIX + code);
        assertThat(lines.get(0)).doesNotContain("raw sentinel");
    }

    private void assertServiceCode(Throwable failure, String code) {
        List<String> lines = new ArrayList<>();
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary =
                serviceBoundary(lines);
        boundary.emitServiceFailure(failure);
        assertThat(lines).containsExactly(PREFIX + code);
    }

    private RuleV1DefaultRuleSetPublicationDiagnosticBoundary serviceBoundary(
            List<String> lines
    ) {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed(lines);
        boundary.beginRunnerConfiguration();
        boundary.beginServiceExecution();
        return boundary;
    }

    private RuleV1DefaultRuleSetPublicationDiagnosticBoundary armed(
            List<String> lines
    ) {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = boundary(
                lines
        );
        assertThat(boundary.tryArm(publicationEnvironment())).isTrue();
        return boundary;
    }

    private RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary(
            List<String> lines
    ) {
        return new RuleV1DefaultRuleSetPublicationDiagnosticBoundary(lines::add);
    }

    private MockEnvironment publicationEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(
                "local",
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        );
        environment.setProperty(enabledProperty(), "true");
        return environment;
    }

    private String enabledProperty() {
        return RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX + ".enabled";
    }

    private void assertState(
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary,
            String expected
    ) {
        assertThat(boundary.state().name()).isEqualTo(expected);
    }

    private Map.Entry<Throwable, String> failure(
            Class<? extends Throwable> type,
            String message,
            String code
    ) {
        Throwable failure;
        if (type == IllegalArgumentException.class) {
            failure = new IllegalArgumentException(message);
        } else {
            failure = new IllegalStateException(message);
        }
        return Map.entry(failure, code);
    }
}
