package com.aifds.backend.rule.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiServicePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class
                    ))
                    .withUserConfiguration(RuleAnalysisClientConfiguration.class);

    @Test
    void requiresBaseUrlWithoutProvidingARuntimeDefault() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsContractDefaults() {
        contextRunner
                .withPropertyValues(
                        "finguardops.ai-service.base-url=http://127.0.0.1:9000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiServiceProperties properties = context.getBean(
                            AiServiceProperties.class
                    );
                    assertThat(properties.baseUrl())
                            .isEqualTo(URI.create("http://127.0.0.1:9000"));
                    assertThat(properties.connectTimeout())
                            .isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.responseTimeout())
                            .isEqualTo(Duration.ofSeconds(3));
                    HttpClient httpClient = context.getBean(
                            RuleAnalysisClientConfiguration.JDK_HTTP_CLIENT_BEAN,
                            HttpClient.class
                    );
                    assertThat(httpClient.connectTimeout())
                            .hasValue(Duration.ofSeconds(1));
                    assertThat(httpClient.followRedirects())
                            .isEqualTo(HttpClient.Redirect.NEVER);
                });
    }

    @Test
    void bindsTimeoutOverridesThroughTheProductionPropertyNames() {
        contextRunner
                .withPropertyValues(
                        "finguardops.ai-service.base-url=https://ai.internal",
                        "finguardops.ai-service.connect-timeout=250ms",
                        "finguardops.ai-service.response-timeout=2s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiServiceProperties properties = context.getBean(
                            AiServiceProperties.class
                    );
                    assertThat(properties.connectTimeout())
                            .isEqualTo(Duration.ofMillis(250));
                    assertThat(properties.responseTimeout())
                            .isEqualTo(Duration.ofSeconds(2));
                    HttpClient httpClient = context.getBean(
                            RuleAnalysisClientConfiguration.JDK_HTTP_CLIENT_BEAN,
                            HttpClient.class
                    );
                    assertThat(httpClient.connectTimeout())
                            .hasValue(Duration.ofMillis(250));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8000",
            "http://localhost:8000/",
            "https://ai.example.test",
            "https://ai.example.test/"
    })
    void acceptsOriginOnlyBaseUrls(String baseUrl) {
        contextRunner
                .withPropertyValues("finguardops.ai-service.base-url=" + baseUrl)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.test/prefix",
            "http://example.test/prefix/",
            "http://example.test//",
            "http://example.test/%2F",
            "http://example.test?query=value",
            "http://example.test#fragment",
            "http://user:password@example.test"
    })
    void rejectsBaseUrlsThatContainAnythingBeyondTheOrigin(String baseUrl) {
        contextRunner
                .withPropertyValues("finguardops.ai-service.base-url=" + baseUrl)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        contextRunner
                .withPropertyValues(
                        "finguardops.ai-service.base-url=http://127.0.0.1:9000",
                        "finguardops.ai-service.connect-timeout=0ms"
                )
                .run(context -> assertThat(context).hasFailed());
    }
}
