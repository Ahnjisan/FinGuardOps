package com.aifds.backend;

import com.aifds.backend.rule.operation.RuleV1DefaultRuleSetPublicationDiagnosticBoundary;
import com.aifds.backend.rule.operation.RuleV1DefaultRuleSetPublicationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

class BackendApplicationPublicationDiagnosticTest {

    @Test
    void initializerArmsOnlyTheDedicatedEnabledProfile() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary normal =
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary();
        GenericApplicationContext normalContext = context("local", false);

        BackendApplication.publicationDiagnosticInitializer(normal)
                .initialize(normalContext);

        assertThat(normal.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State.UNARMED
        );
        assertThat(normalContext.getBeanFactory().getBean(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.class
                        .getName()
        )).isSameAs(normal);

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary publication =
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary();
        GenericApplicationContext publicationContext = context(
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE,
                true
        );

        BackendApplication.publicationDiagnosticInitializer(publication)
                .initialize(publicationContext);

        assertThat(publication.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State
                        .ARMED_PRE_RUN
        );
    }

    @Test
    void initializerDiagnosticFailureDoesNotCreateStartupFailure() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary =
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary();
        GenericApplicationContext context = context(
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE,
                true
        );
        context.getBeanFactory().registerSingleton(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.class.getName(),
                new Object()
        );

        assertThatCode(() -> BackendApplication
                .publicationDiagnosticInitializer(boundary)
                .initialize(context)).doesNotThrowAnyException();
        assertThat(boundary.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State.UNARMED
        );
    }

    @Test
    void outerBoundaryEmitsStartupMarkerAndRethrowsSameFailure() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed();
        IllegalStateException failure = new IllegalStateException(
                "startup raw sentinel"
        );

        Capture capture = captureError(() -> catchThrowable(() ->
                BackendApplication.runWithPublicationDiagnosticBoundary(
                        () -> {
                            throw failure;
                        },
                        boundary
                )
        ));

        assertThat(capture.failure()).isSameAs(failure);
        assertThat(capture.stderr()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.WIRE_PREFIX
                        + "RULE_PUBLICATION_BACKEND_STARTUP_FAILED"
                        + System.lineSeparator()
        );
        assertThat(capture.stderr()).doesNotContain("raw sentinel");
    }

    @Test
    void refreshBoundaryEmitsContextMarkerAndRethrowsSameFailure() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed();
        IllegalStateException failure = new IllegalStateException(
                "context raw sentinel"
        );
        GenericApplicationContext context = new GenericApplicationContext();
        context.addBeanFactoryPostProcessor(ignored -> {
            throw failure;
        });
        BackendApplication.PublicationDiagnosticSpringApplication application =
                new BackendApplication.PublicationDiagnosticSpringApplication(
                        boundary
                );

        Capture capture = captureError(() -> catchThrowable(() ->
                BackendApplication.runWithPublicationDiagnosticBoundary(
                        () -> {
                            application.refresh(context);
                            return context;
                        },
                        boundary
                )
        ));

        assertThat(capture.failure()).isSameAs(failure);
        assertThat(boundary.state()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State
                        .CONTEXT_REFRESH
        );
        assertThat(capture.stderr()).isEqualTo(
                RuleV1DefaultRuleSetPublicationDiagnosticBoundary.WIRE_PREFIX
                        + "RULE_PUBLICATION_CONTEXT_REFRESH_FAILED"
                        + System.lineSeparator()
        );
        assertThat(capture.stderr()).doesNotContain("raw sentinel");
    }

    @Test
    void failureAfterRefreshAndBeforeRunnerUsesPreRunnerMarker() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary = armed();
        GenericApplicationContext context = new GenericApplicationContext();
        BackendApplication.PublicationDiagnosticSpringApplication application =
                new BackendApplication.PublicationDiagnosticSpringApplication(
                        boundary
                );
        application.refresh(context);
        IllegalStateException failure = new IllegalStateException(
                "pre-runner raw sentinel"
        );

        try {
            Capture capture = captureError(() -> catchThrowable(() ->
                    BackendApplication.runWithPublicationDiagnosticBoundary(
                            () -> {
                                throw failure;
                            },
                            boundary
                    )
            ));

            assertThat(capture.failure()).isSameAs(failure);
            assertThat(boundary.state()).isEqualTo(
                    RuleV1DefaultRuleSetPublicationDiagnosticBoundary.State
                            .CONTEXT_REFRESHED_PRE_RUN
            );
            assertThat(capture.stderr()).isEqualTo(
                    RuleV1DefaultRuleSetPublicationDiagnosticBoundary.WIRE_PREFIX
                            + "RULE_PUBLICATION_PRE_RUNNER_FAILED"
                            + System.lineSeparator()
            );
            assertThat(capture.stderr()).doesNotContain("raw sentinel");
        } finally {
            context.close();
        }
    }

    @Test
    void failuresBeforeArmAndAfterRunnerEntryDoNotEmitStartupMarker() {
        assertOuterFailureHasNoMarker(
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary()
        );

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary entered = armed();
        enterPreRunner(entered);
        entered.beginRunnerConfiguration();
        assertOuterFailureHasNoMarker(entered);

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary committed = armed();
        enterPreRunner(committed);
        committed.beginRunnerConfiguration();
        committed.beginServiceExecution();
        committed.publicationCommitted();
        assertOuterFailureHasNoMarker(committed);

        RuleV1DefaultRuleSetPublicationDiagnosticBoundary succeeded = armed();
        enterPreRunner(succeeded);
        succeeded.beginRunnerConfiguration();
        succeeded.beginServiceExecution();
        succeeded.publicationCommitted();
        succeeded.runnerSucceeded();
        assertOuterFailureHasNoMarker(succeeded);
    }

    private void assertOuterFailureHasNoMarker(
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary
    ) {
        IllegalStateException failure = new IllegalStateException("raw sentinel");
        Capture capture = captureError(() -> catchThrowable(() ->
                BackendApplication.runWithPublicationDiagnosticBoundary(
                        () -> {
                            throw failure;
                        },
                        boundary
                )
        ));
        assertThat(capture.failure()).isSameAs(failure);
        assertThat(capture.stderr()).isEmpty();
    }

    private RuleV1DefaultRuleSetPublicationDiagnosticBoundary armed() {
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary =
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(
                "local",
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        );
        environment.setProperty(
                RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                        + ".enabled",
                "true"
        );
        assertThat(boundary.tryArm(environment)).isTrue();
        return boundary;
    }

    private void enterPreRunner(
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary
    ) {
        boundary.beginContextRefresh();
        boundary.contextRefreshed();
    }

    private GenericApplicationContext context(
            String profile,
            boolean enabled
    ) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource(
                        "test",
                        java.util.Map.of(
                                RuleV1DefaultRuleSetPublicationRunner
                                        .PROPERTY_PREFIX + ".enabled",
                                Boolean.toString(enabled)
                        )
                )
        );
        return context;
    }

    private Capture captureError(java.util.function.Supplier<Throwable> body) {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream replacement = new PrintStream(
                bytes,
                true,
                StandardCharsets.UTF_8
        )) {
            System.setErr(replacement);
            Throwable failure = body.get();
            replacement.flush();
            return new Capture(
                    failure,
                    bytes.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setErr(original);
        }
    }

    private record Capture(Throwable failure, String stderr) {
    }
}
