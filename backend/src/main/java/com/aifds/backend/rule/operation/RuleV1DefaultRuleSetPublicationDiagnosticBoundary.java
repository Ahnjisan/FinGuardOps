package com.aifds.backend.rule.operation;

import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local, profile-scoped failure marker boundary for the Rule v1
 * default-publication one-shot runner.
 */
public final class RuleV1DefaultRuleSetPublicationDiagnosticBoundary {

    public static final String STARTUP_FAILED =
            "RULE_PUBLICATION_BACKEND_STARTUP_FAILED";
    public static final String CONTEXT_REFRESH_FAILED =
            "RULE_PUBLICATION_CONTEXT_REFRESH_FAILED";
    public static final String PRE_RUNNER_FAILED =
            "RULE_PUBLICATION_PRE_RUNNER_FAILED";
    public static final String RUNNER_CONFIGURATION_FAILED =
            "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED";
    public static final String SERVICE_EXECUTION_FAILED =
            "RULE_PUBLICATION_SERVICE_EXECUTION_FAILED";
    public static final String WIRE_PREFIX =
            "FINGUARDOPS_RULE_PUBLICATION_FAILURE=";

    private static final int MAX_CAUSE_DEPTH = 64;
    private static final String ENABLED_PROPERTY =
            RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX + ".enabled";

    private static final Set<String> CONFIGURATION_CODES = Set.of(
            "RULE_PUBLICATION_RUNNER_PRODUCTION_PROFILE_REJECTED",
            "RULE_PUBLICATION_RUNNER_APPROVED_PROFILE_REQUIRED",
            "RULE_PUBLICATION_RUNNER_NON_WEB_MODE_REQUIRED",
            "RULE_PUBLICATION_RUNNER_CONFIRMATION_REJECTED",
            "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED",
            "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_NOT_FUTURE"
    );
    private static final Set<String> SERVICE_CODES = Set.of(
            "RULE_PUBLICATION_SERVICE_DEFAULT_SET_INCOMPLETE",
            "RULE_PUBLICATION_SERVICE_IDENTITY_MISMATCH",
            "RULE_PUBLICATION_SERVICE_FRAUD_RULE_INACTIVE",
            "RULE_PUBLICATION_SERVICE_VERSION_PERIOD_INVALID",
            "RULE_PUBLICATION_SERVICE_VERSION_STATUS_INVALID",
            "RULE_PUBLICATION_SERVICE_DRAFT_METADATA_INVALID",
            "RULE_PUBLICATION_SERVICE_EFFECTIVE_FROM_EXPIRED",
            "RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID"
    );
    private static final Map<FailureIdentity, String> APPROVED_IDENTITIES =
            Map.ofEntries(
                    identity(
                            IllegalStateException.class,
                            "Rule v1 default publication is forbidden "
                                    + "in production",
                            "RULE_PUBLICATION_RUNNER_PRODUCTION_PROFILE_REJECTED"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Rule v1 default publication requires its operation "
                                    + "profile and a local, dev, or test profile",
                            "RULE_PUBLICATION_RUNNER_APPROVED_PROFILE_REQUIRED"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Rule v1 default publication requires "
                                    + "spring.main.web-application-type=none",
                            "RULE_PUBLICATION_RUNNER_NON_WEB_MODE_REQUIRED"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Rule v1 default publication confirmation "
                                    + "does not match",
                            "RULE_PUBLICATION_RUNNER_CONFIRMATION_REJECTED"
                    ),
                    identity(
                            IllegalArgumentException.class,
                            "Rule v1 default effectiveFrom must be a canonical "
                                    + "UTC Instant",
                            "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED"
                    ),
                    identity(
                            IllegalArgumentException.class,
                            "Rule v1 default effectiveFrom must be canonical UTC "
                                    + "with at most microsecond precision",
                            "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED"
                    ),
                    identity(
                            IllegalArgumentException.class,
                            "Rule v1 default effectiveFrom must be in the "
                                    + "future",
                            "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_NOT_FUTURE"
                    ),
                    identity(
                            IllegalStateException.class,
                            "The complete V5 default Rule v1 set does not "
                                    + "exist",
                            "RULE_PUBLICATION_SERVICE_DEFAULT_SET_INCOMPLETE"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Default Rule v1 identity does not match the V5 "
                                    + "contract",
                            "RULE_PUBLICATION_SERVICE_IDENTITY_MISMATCH"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Default Rule v1 FraudRules must be " + "ACTIVE",
                            "RULE_PUBLICATION_SERVICE_FRAUD_RULE_INACTIVE"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Default Rule v1 versions must be " + "open-ended",
                            "RULE_PUBLICATION_SERVICE_VERSION_PERIOD_INVALID"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Default Rule v1 versions must be all DRAFT or all "
                                    + "PUBLISHED",
                            "RULE_PUBLICATION_SERVICE_VERSION_STATUS_INVALID"
                    ),
                    identity(
                            IllegalStateException.class,
                            "Default Rule v1 DRAFT period metadata must be "
                                    + "unset",
                            "RULE_PUBLICATION_SERVICE_DRAFT_METADATA_INVALID"
                    ),
                    identity(
                            IllegalArgumentException.class,
                            "effectiveFrom must be later than the publication "
                                    + "time",
                            "RULE_PUBLICATION_SERVICE_EFFECTIVE_FROM_EXPIRED"
                    ),
                    identity(
                            IllegalArgumentException.class,
                            "amountThreshold must be a positive canonical "
                                    + "integer string within NUMERIC(19,4) integer range",
                            "RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID"
                    )
            );

    private final AtomicReference<State> state =
            new AtomicReference<>(State.UNARMED);
    private final AtomicBoolean compromised = new AtomicBoolean(false);
    private final AtomicBoolean markerAttempted = new AtomicBoolean(false);
    private final MarkerWriter markerWriter;

    public RuleV1DefaultRuleSetPublicationDiagnosticBoundary() {
        this(line -> System.err.println(line));
    }

    RuleV1DefaultRuleSetPublicationDiagnosticBoundary(
            MarkerWriter markerWriter
    ) {
        this.markerWriter = markerWriter;
    }

    public boolean tryArm(Environment environment) {
        try {
            if (environment == null
                    || !hasPublicationProfile(environment)
                    || !"true".equals(environment.getProperty(
                    ENABLED_PROPERTY
            ))) {
                return false;
            }
            if (!state.compareAndSet(State.UNARMED, State.ARMED_PRE_RUN)) {
                compromised.set(true);
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void beginContextRefresh() {
        advance(State.ARMED_PRE_RUN, State.CONTEXT_REFRESH);
    }

    public void contextRefreshed() {
        advance(State.CONTEXT_REFRESH, State.CONTEXT_REFRESHED_PRE_RUN);
    }

    public void beginRunnerConfiguration() {
        advance(State.CONTEXT_REFRESHED_PRE_RUN, State.RUNNER_CONFIGURATION);
    }

    public void beginServiceExecution() {
        advance(State.RUNNER_CONFIGURATION, State.SERVICE_EXECUTION);
    }

    public void publicationCommitted() {
        advance(State.SERVICE_EXECUTION, State.PUBLICATION_COMMITTED);
    }

    public void runnerSucceeded() {
        advance(State.PUBLICATION_COMMITTED, State.RUNNER_SUCCEEDED);
    }

    public void emitStartupFailure(Throwable failure) {
        State current = state.get();
        if (current == State.ARMED_PRE_RUN) {
            emitForState(State.ARMED_PRE_RUN, STARTUP_FAILED);
        } else if (current == State.CONTEXT_REFRESH) {
            emitForState(State.CONTEXT_REFRESH, CONTEXT_REFRESH_FAILED);
        } else if (current == State.CONTEXT_REFRESHED_PRE_RUN) {
            emitForState(State.CONTEXT_REFRESHED_PRE_RUN, PRE_RUNNER_FAILED);
        }
    }

    public void emitConfigurationFailure(Throwable failure) {
        emitForState(
                State.RUNNER_CONFIGURATION,
                classify(failure, CONFIGURATION_CODES, RUNNER_CONFIGURATION_FAILED)
        );
    }

    public void emitServiceFailure(Throwable failure) {
        emitForState(
                State.SERVICE_EXECUTION,
                classify(failure, SERVICE_CODES, SERVICE_EXECUTION_FAILED)
        );
    }

    public State state() {
        return state.get();
    }

    boolean markerAttempted() {
        return markerAttempted.get();
    }

    private boolean hasPublicationProfile(Environment environment) {
        for (String activeProfile : environment.getActiveProfiles()) {
            if (RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE.equals(
                    activeProfile
            )) {
                return true;
            }
        }
        return false;
    }

    private void advance(State expected, State next) {
        if (state.get() == State.UNARMED) {
            return;
        }
        if (compromised.get() || !state.compareAndSet(expected, next)) {
            compromised.set(true);
            throw new IllegalStateException(
                    "Rule publication diagnostic state transition failed"
            );
        }
    }

    private void emitForState(State expected, String code) {
        if (compromised.get() || state.get() != expected) {
            return;
        }
        emit(code);
    }

    private void emit(String code) {
        if (!markerAttempted.compareAndSet(false, true)) {
            return;
        }
        try {
            markerWriter.write(WIRE_PREFIX + code);
        } catch (Throwable ignored) {
            // The diagnostic must never replace or mask the product failure.
        }
    }

    private String classify(
            Throwable failure,
            Set<String> phaseCodes,
            String broadCode
    ) {
        try {
            Set<Throwable> seen = Collections.newSetFromMap(
                    new IdentityHashMap<>()
            );
            Throwable current = failure;
            String selected = null;
            int matches = 0;
            while (current != null) {
                if (seen.size() >= MAX_CAUSE_DEPTH || !seen.add(current)) {
                    return broadCode;
                }
                String code = APPROVED_IDENTITIES.get(new FailureIdentity(
                        current.getClass(),
                        current.getMessage()
                ));
                if (code != null) {
                    selected = code;
                    matches++;
                }
                current = current.getCause();
            }
            if (matches == 1 && phaseCodes.contains(selected)) {
                return selected;
            }
        } catch (Throwable ignored) {
            // Classification is diagnostic-only and must fail closed.
        }
        return broadCode;
    }

    private static Map.Entry<FailureIdentity, String> identity(
            Class<? extends Throwable> type,
            String message,
            String code
    ) {
        return Map.entry(new FailureIdentity(type, message), code);
    }

    public enum State {
        UNARMED,
        ARMED_PRE_RUN,
        CONTEXT_REFRESH,
        CONTEXT_REFRESHED_PRE_RUN,
        RUNNER_CONFIGURATION,
        SERVICE_EXECUTION,
        PUBLICATION_COMMITTED,
        RUNNER_SUCCEEDED
    }

    @FunctionalInterface
    interface MarkerWriter {
        void write(String line) throws Throwable;
    }

    private record FailureIdentity(Class<?> type, String message) {
    }
}
