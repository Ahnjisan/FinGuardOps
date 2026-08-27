package com.aifds.backend.externalrisk.client.config;

import com.aifds.backend.common.config.TimeConfiguration;
import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.client.ExternalRiskHttpAdapter;
import com.aifds.backend.externalrisk.service.ExternalRiskLookupCommandReader;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExternalRiskHttpConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class
                    ))
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
                            ExternalRiskHttpConfiguration.class
                    );

    @Test
    void defaultAndTestOnlyContextsCreateNoNetworkOrWorkflowBeans() {
        contextRunner.run(this::assertHttpBeansAbsent);
        profiled("test").run(this::assertHttpBeansAbsent);
    }

    @Test
    void approvedNonProductionRequiresDedicatedProfileAndEnabledProperty() {
        enabled("local", ExternalRiskHttpConfiguration.HTTP_PROFILE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ExternalRiskHttpAdapter.class);
                    assertThat(context).hasSingleBean(ExternalRiskPolicyService.class);
                    assertThat(context).hasSingleBean(
                            ExternalRiskRuleAnalysisCoordinator.class
                    );
                    ExternalRiskHttpProperties properties = context.getBean(
                            ExternalRiskHttpProperties.class
                    );
                    assertThat(properties.connectTimeout())
                            .isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.readTimeout())
                            .isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.maxResponseBytes()).isEqualTo(65_536);
                    assertThat(properties.toString()).doesNotContain("test-api-key");
                    HttpClient httpClient = context.getBean(
                            ExternalRiskHttpConfiguration.JDK_HTTP_CLIENT_BEAN,
                            HttpClient.class
                    );
                    assertThat(httpClient.followRedirects())
                            .isEqualTo(HttpClient.Redirect.NEVER);
                    assertThat(httpClient.connectTimeout())
                            .contains(Duration.ofSeconds(2));
                });
        enabled("local").run(context -> assertThat(context).hasFailed());
        profiled("local", ExternalRiskHttpConfiguration.HTTP_PROFILE)
                .run(this::assertHttpBeansAbsent);
        enabledWithUrl(
                new String[]{"test", ExternalRiskHttpConfiguration.HTTP_PROFILE},
                "http://[::1]:9000"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionRequiresEnabledHttpsAndCompleteProperties() {
        profiled("prod").run(context -> assertThat(context).hasFailed());
        enabledWithUrl("prod", "http://127.0.0.1:9000")
                .run(context -> assertThat(context).hasFailed());
        enabledWithUrl("production", "https://risk.example.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ExternalRiskHttpAdapter.class);
                    assertThat(context).hasSingleBean(
                            ExternalRiskRuleAnalysisCoordinator.class
                    );
                });
    }

    @Test
    void rejectsUnapprovedProfileRemoteHttpAndInvalidProperties() {
        profiled(ExternalRiskHttpConfiguration.HTTP_PROFILE)
                .run(context -> assertThat(context).hasFailed());
        enabledWithUrl(
                new String[]{"dev", ExternalRiskHttpConfiguration.HTTP_PROFILE},
                "http://risk.example.test"
        ).run(context -> assertThat(context).hasFailed());
        enabled("test", ExternalRiskHttpConfiguration.HTTP_PROFILE)
                .withPropertyValues(
                        ExternalRiskHttpConfiguration.PROPERTY_PREFIX
                                + ".max-response-bytes=65537"
                )
                .run(context -> assertThat(context).hasFailed());
        enabled("test", ExternalRiskHttpConfiguration.HTTP_PROFILE)
                .withPropertyValues(
                        ExternalRiskHttpConfiguration.PROPERTY_PREFIX
                                + ".read-timeout=0ms"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingCredentialAndOriginPathsWithoutExposingValues() {
        profiled("test", ExternalRiskHttpConfiguration.HTTP_PROFILE)
                .withPropertyValues(
                        ExternalRiskHttpConfiguration.PROPERTY_PREFIX
                                + ".enabled=true",
                        ExternalRiskHttpConfiguration.PROPERTY_PREFIX
                                + ".base-url=http://127.0.0.1:9000"
                )
                .run(context -> assertThat(context).hasFailed());
        enabledWithUrl(
                new String[]{"test", ExternalRiskHttpConfiguration.HTTP_PROFILE},
                "http://user:secret@127.0.0.1:9000/path"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(String.valueOf(context.getStartupFailure()))
                    .doesNotContain("user:secret");
        });
    }

    private ApplicationContextRunner enabled(String... profiles) {
        return enabledWithUrl(profiles, "http://127.0.0.1:9000");
    }

    private ApplicationContextRunner enabledWithUrl(
            String profile,
            String baseUrl
    ) {
        return enabledWithUrl(new String[]{profile}, baseUrl);
    }

    private ApplicationContextRunner enabledWithUrl(
            String[] profiles,
            String baseUrl
    ) {
        return profiled(profiles).withPropertyValues(
                ExternalRiskHttpConfiguration.PROPERTY_PREFIX + ".enabled=true",
                ExternalRiskHttpConfiguration.PROPERTY_PREFIX
                        + ".base-url=" + baseUrl,
                ExternalRiskHttpConfiguration.PROPERTY_PREFIX
                        + ".api-key=test-api-key"
        );
    }

    private ApplicationContextRunner profiled(String... profiles) {
        return contextRunner.withInitializer(context -> context.getEnvironment()
                .setActiveProfiles(profiles));
    }

    private void assertHttpBeansAbsent(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context
    ) {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(ExternalRiskHttpAdapter.class);
        assertThat(context).doesNotHaveBean(ExternalRiskPolicyService.class);
        assertThat(context).doesNotHaveBean(
                ExternalRiskRuleAnalysisCoordinator.class
        );
    }
}
