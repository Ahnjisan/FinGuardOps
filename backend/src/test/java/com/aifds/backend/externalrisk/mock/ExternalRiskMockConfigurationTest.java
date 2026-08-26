package com.aifds.backend.externalrisk.mock;

import com.aifds.backend.common.config.TimeConfiguration;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.service.ExternalRiskLookupCommandReader;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExternalRiskMockConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            ExternalRiskLookupCommandReader.class,
                            () -> mock(ExternalRiskLookupCommandReader.class)
                    )
                    .withBean(
                            RuleAnalysisOrchestrationService.class,
                            () -> mock(RuleAnalysisOrchestrationService.class)
                    )
                    .withUserConfiguration(
                            TimeConfiguration.class,
                            ExternalRiskMockConfiguration.class
                    );

    @Test
    void approvedEnvironmentDedicatedProfileEnabledAndScenarioCreateBeans() {
        for (String environmentProfile : new String[]{"local", "dev", "test"}) {
            enabledContext(environmentProfile)
                    .run(context -> {
                        assertThat(context).hasSingleBean(
                                ExternalRiskMockConfiguration.class
                        );
                        assertThat(context).hasSingleBean(
                                ExternalRiskMockAdapter.class
                        );
                        assertThat(context).hasSingleBean(
                                ExternalRiskPolicyService.class
                        );
                        assertThat(context).hasSingleBean(
                                ExternalRiskRuleAnalysisCoordinator.class
                        );
                        Clock clock = context.getBean(Clock.class);
                        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);

                        var snapshot = context.getBean(
                                ExternalRiskPolicyService.class
                        ).lookup(command());
                        assertThat(snapshot.policyResult()).isEqualTo(
                                ExternalRiskPolicyResult.UNMATCHED
                        );
                    });
        }
    }

    @Test
    void dedicatedProfileIsRequiredForEveryMockBean() {
        contextRunner
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles("local"))
                .withPropertyValues(
                        ExternalRiskMockConfiguration.PROPERTY_PREFIX
                                + ".enabled=true",
                        ExternalRiskMockConfiguration.PROPERTY_PREFIX
                                + ".scenario=UNMATCHED"
                )
                .run(context -> assertMockBeansAbsent(context));
    }

    @Test
    void enabledDefaultsFalseAndExplicitFalseCreatesNoMockBeans() {
        profiledContext("local").run(context -> assertMockBeansAbsent(context));
        profiledContext("local")
                .withPropertyValues(
                        ExternalRiskMockConfiguration.PROPERTY_PREFIX
                                + ".enabled=false",
                        ExternalRiskMockConfiguration.PROPERTY_PREFIX
                                + ".scenario=UNMATCHED"
                )
                .run(context -> assertMockBeansAbsent(context));
    }

    @Test
    void enabledMockFailsFastWithoutApprovedEnvironment() {
        enabledContextWithoutScenario(
                ExternalRiskMockConfiguration.MOCK_PROFILE
        ).withPropertyValues(
                ExternalRiskMockConfiguration.PROPERTY_PREFIX
                        + ".scenario=UNMATCHED"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "External Risk Mock requires a local, dev, or test profile"
                    );
        });
    }

    @Test
    void productionOrProdAlwaysWinsOverApprovedEnvironment() {
        for (String productionProfile : new String[]{"production", "prod"}) {
            enabledContextWithoutScenario(
                    "local",
                    productionProfile,
                    ExternalRiskMockConfiguration.MOCK_PROFILE
            ).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "External Risk Mock is forbidden in production"
                        );
            });
        }
    }

    @Test
    void scenarioIsRequiredAfterEnvironmentSafetyGate() {
        enabledContextWithoutScenario(
                "local",
                ExternalRiskMockConfiguration.MOCK_PROFILE
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("External Risk Mock scenario is required");
        });
    }

    @Test
    void invalidScenarioValueFailsPropertyBinding() {
        enabledContextWithoutScenario(
                "local",
                ExternalRiskMockConfiguration.MOCK_PROFILE
        ).withPropertyValues(
                ExternalRiskMockConfiguration.PROPERTY_PREFIX
                        + ".scenario=NOT_A_SUPPORTED_SCENARIO"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasCauseInstanceOf(
                            ConfigurationPropertiesBindException.class
                    )
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void normalApplicationProfileKeepsClockButCreatesNoExternalRiskBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertMockBeansAbsent(context);
        });
    }

    private ApplicationContextRunner enabledContext(String environmentProfile) {
        return enabledContextWithoutScenario(
                environmentProfile,
                ExternalRiskMockConfiguration.MOCK_PROFILE
        ).withPropertyValues(
                ExternalRiskMockConfiguration.PROPERTY_PREFIX + ".scenario=UNMATCHED"
        );
    }

    private ApplicationContextRunner profiledContext(String environmentProfile) {
        return contextRunner.withInitializer(context -> context.getEnvironment()
                .setActiveProfiles(
                        environmentProfile,
                        ExternalRiskMockConfiguration.MOCK_PROFILE
                ));
    }

    private ApplicationContextRunner enabledContextWithoutScenario(
            String... profiles
    ) {
        return contextRunner
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(profiles))
                .withPropertyValues(
                        ExternalRiskMockConfiguration.PROPERTY_PREFIX
                                + ".enabled=true"
                );
    }

    private void assertMockBeansAbsent(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context
    ) {
        assertThat(context).doesNotHaveBean(ExternalRiskMockConfiguration.class);
        assertThat(context).doesNotHaveBean(ExternalRiskMockAdapter.class);
        assertThat(context).doesNotHaveBean(ExternalRiskPolicyService.class);
        assertThat(context).doesNotHaveBean(
                ExternalRiskRuleAnalysisCoordinator.class
        );
    }

    private ExternalRiskLookupCommand command() {
        return new ExternalRiskLookupCommand(
                UUID.fromString("53000000-0000-4000-8000-000000000001"),
                TransactionType.ACCOUNT_TRANSFER,
                Instant.EPOCH,
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        );
    }
}
