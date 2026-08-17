package com.aifds.backend.rule.operation;

import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationResult;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleV1DefaultRuleSetPublicationRunnerTest {

    private static final Instant CLOCK_INSTANT =
            Instant.parse("2026-08-17T05:00:00.123456Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final String EFFECTIVE_FROM =
            "2026-08-17T05:00:00.123457Z";
    private static final Instant EFFECTIVE_FROM_INSTANT =
            Instant.parse(EFFECTIVE_FROM);

    @Test
    void runnerBeanIsAbsentByDefaultAndWhenEnabledWithoutProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(RunnerConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(
                        RuleV1DefaultRuleSetPublicationRunner.class
                ));

        new ApplicationContextRunner()
                .withUserConfiguration(RunnerConfiguration.class)
                .withPropertyValues(
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".enabled=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(
                        RuleV1DefaultRuleSetPublicationRunner.class
                ));
    }

    @Test
    void runnerBeanRequiresEnabledPropertyWithDedicatedProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(RunnerConfiguration.class)
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(
                                "local",
                                RuleV1DefaultRuleSetPublicationRunner
                                        .PUBLICATION_PROFILE
                        ))
                .run(context -> assertThat(context).doesNotHaveBean(
                        RuleV1DefaultRuleSetPublicationRunner.class
                ));

        new ApplicationContextRunner()
                .withUserConfiguration(RunnerConfiguration.class)
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(
                                "local",
                                RuleV1DefaultRuleSetPublicationRunner
                                        .PUBLICATION_PROFILE
                        ))
                .withPropertyValues(
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".enabled=false"
                )
                .run(context -> assertThat(context).doesNotHaveBean(
                        RuleV1DefaultRuleSetPublicationRunner.class
                ));

        new ApplicationContextRunner()
                .withUserConfiguration(RunnerConfiguration.class)
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(
                                "local",
                                RuleV1DefaultRuleSetPublicationRunner
                                        .PUBLICATION_PROFILE
                        ))
                .withPropertyValues(
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".enabled=true",
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".confirmation="
                                + RuleV1DefaultRuleSetPublicationRunner
                                .REQUIRED_CONFIRMATION,
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".effective-from=" + EFFECTIVE_FROM
                )
                .run(context -> assertThat(context).hasSingleBean(
                        RuleV1DefaultRuleSetPublicationRunner.class
                ));
    }

    @Test
    void beanExistsButFailsFastWithoutApprovedEnvironmentProfile() {
        publicationContextRunner("rule-v1-default-publication")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            RuleV1DefaultRuleSetPublicationRunner.class
                    );
                    RuleV1DefaultRuleSetPublicationService service =
                            context.getBean(
                                    RuleV1DefaultRuleSetPublicationService.class
                            );

                    assertThatThrownBy(() -> context.getBean(
                            RuleV1DefaultRuleSetPublicationRunner.class
                    ).run(arguments()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("local, dev, or test");
                    verify(service, never()).publish(
                            org.mockito.ArgumentMatchers.any()
                    );
                });
    }

    @Test
    void beanExistsButFailsFastWhenLocalAndProductionProfilesAreMixed() {
        publicationContextRunner(
                "local",
                "prod",
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        ).run(context -> {
            assertThat(context).hasSingleBean(
                    RuleV1DefaultRuleSetPublicationRunner.class
            );
            RuleV1DefaultRuleSetPublicationService service = context.getBean(
                    RuleV1DefaultRuleSetPublicationService.class
            );

            assertThatThrownBy(() -> context.getBean(
                    RuleV1DefaultRuleSetPublicationRunner.class
            ).run(arguments()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forbidden in production");
            verify(service, never()).publish(
                    org.mockito.ArgumentMatchers.any()
            );
        });
    }

    @Test
    void validApplicationContextCallsServiceExactlyOnce() {
        publicationContextRunner(
                "local",
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        ).run(context -> {
            RuleV1DefaultRuleSetPublicationService service = context.getBean(
                    RuleV1DefaultRuleSetPublicationService.class
            );
            when(service.publish(EFFECTIVE_FROM_INSTANT)).thenReturn(result());

            context.getBean(RuleV1DefaultRuleSetPublicationRunner.class)
                    .run(arguments());

            verify(service).publish(EFFECTIVE_FROM_INSTANT);
        });
    }

    @Test
    void missingAndMismatchedConfirmationAreRejectedWithoutCallingService() {
        RuleV1DefaultRuleSetPublicationService service = mock(
                RuleV1DefaultRuleSetPublicationService.class
        );

        assertThatThrownBy(() -> runner(service, "").run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation");
        assertThatThrownBy(() -> runner(service, "WRONG").run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation");

        verify(service, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void productionProfileIsRejectedBeforeCallingService() {
        RuleV1DefaultRuleSetPublicationService service = mock(
                RuleV1DefaultRuleSetPublicationService.class
        );
        MockEnvironment environment = approvedEnvironment();
        environment.setActiveProfiles(
                "local",
                "production",
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        );
        RuleV1DefaultRuleSetPublicationRunner runner = newRunner(
                service,
                environment,
                RuleV1DefaultRuleSetPublicationRunner.REQUIRED_CONFIRMATION,
                EFFECTIVE_FROM
        );

        assertThatThrownBy(() -> runner.run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden in production");
        verify(service, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runnerRequiresApprovedEnvironmentAndNonWebMode() {
        RuleV1DefaultRuleSetPublicationService service = mock(
                RuleV1DefaultRuleSetPublicationService.class
        );
        MockEnvironment unapproved = new MockEnvironment();
        unapproved.setActiveProfiles(
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        );
        unapproved.setProperty("spring.main.web-application-type", "none");
        assertThatThrownBy(() -> newRunner(
                service,
                unapproved,
                RuleV1DefaultRuleSetPublicationRunner.REQUIRED_CONFIRMATION,
                EFFECTIVE_FROM
        ).run(arguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local, dev, or test");

        MockEnvironment web = approvedEnvironment();
        web.setProperty("spring.main.web-application-type", "servlet");
        assertThatThrownBy(() -> newRunner(
                service,
                web,
                RuleV1DefaultRuleSetPublicationRunner.REQUIRED_CONFIRMATION,
                EFFECTIVE_FROM
        ).run(arguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-application-type=none");

        verify(service, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runnerRejectsMissingNonCanonicalCurrentAndPastEffectiveFrom() {
        RuleV1DefaultRuleSetPublicationService service = mock(
                RuleV1DefaultRuleSetPublicationService.class
        );
        for (String invalid : List.of(
                "",
                "2999-01-01T09:00:00+09:00",
                "2999-01-01T00:00:00.000000001Z",
                CLOCK_INSTANT.toString(),
                CLOCK_INSTANT.minusNanos(1_000).toString()
        )) {
            assertThatThrownBy(() -> newRunner(
                    service,
                    approvedEnvironment(),
                    RuleV1DefaultRuleSetPublicationRunner
                            .REQUIRED_CONFIRMATION,
                    invalid
            ).run(arguments())).isInstanceOf(RuntimeException.class);
        }
        verify(service, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runnerCallsServiceExactlyOnceAndPropagatesFailures() {
        RuleV1DefaultRuleSetPublicationService service = mock(
                RuleV1DefaultRuleSetPublicationService.class
        );
        when(service.publish(EFFECTIVE_FROM_INSTANT)).thenReturn(result());

        runner(
                service,
                RuleV1DefaultRuleSetPublicationRunner.REQUIRED_CONFIRMATION
        ).run(arguments());

        verify(service).publish(EFFECTIVE_FROM_INSTANT);

        RuleV1DefaultRuleSetPublicationService failing = mock(
                RuleV1DefaultRuleSetPublicationService.class
        );
        IllegalStateException failure = new IllegalStateException(
                "publication failed"
        );
        when(failing.publish(EFFECTIVE_FROM_INSTANT)).thenThrow(failure);
        assertThatThrownBy(() -> runner(
                failing,
                RuleV1DefaultRuleSetPublicationRunner.REQUIRED_CONFIRMATION
        ).run(arguments())).isSameAs(failure);
        verify(failing).publish(EFFECTIVE_FROM_INSTANT);
    }

    private RuleV1DefaultRuleSetPublicationRunner runner(
            RuleV1DefaultRuleSetPublicationService service,
            String confirmation
    ) {
        return newRunner(
                service,
                approvedEnvironment(),
                confirmation,
                EFFECTIVE_FROM
        );
    }

    private RuleV1DefaultRuleSetPublicationRunner newRunner(
            RuleV1DefaultRuleSetPublicationService service,
            MockEnvironment environment,
            String confirmation,
            String effectiveFrom
    ) {
        return new RuleV1DefaultRuleSetPublicationRunner(
                service,
                environment,
                FIXED_CLOCK,
                confirmation,
                effectiveFrom
        );
    }

    private MockEnvironment approvedEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(
                "local",
                RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        );
        environment.setProperty("spring.main.web-application-type", "none");
        return environment;
    }

    private DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }

    private ApplicationContextRunner publicationContextRunner(
            String... profiles
    ) {
        return new ApplicationContextRunner()
                .withUserConfiguration(RunnerConfiguration.class)
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(profiles))
                .withPropertyValues(
                        "spring.main.web-application-type=none",
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".enabled=true",
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".confirmation="
                                + RuleV1DefaultRuleSetPublicationRunner
                                .REQUIRED_CONFIRMATION,
                        RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX
                                + ".effective-from=" + EFFECTIVE_FROM
                );
    }

    private RuleV1DefaultRuleSetPublicationResult result() {
        return new RuleV1DefaultRuleSetPublicationResult(
                RuleV1DefaultRuleSetPublicationResult.PublicationOutcome
                        .PUBLISHED,
                List.of(
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000001"
                        ),
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000002"
                        ),
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000003"
                        ),
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000004"
                        )
                ),
                EFFECTIVE_FROM_INSTANT,
                Instant.parse("2026-08-17T05:00:00Z"),
                "31299ea02656c1a5c72f2ead74b5ca468d087b4080249e5915d8882164d8121e"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RuleV1DefaultRuleSetPublicationRunner.class)
    static class RunnerConfiguration {

        @Bean
        RuleV1DefaultRuleSetPublicationService publicationService() {
            return mock(RuleV1DefaultRuleSetPublicationService.class);
        }

        @Bean
        Clock clock() {
            return FIXED_CLOCK;
        }
    }
}
