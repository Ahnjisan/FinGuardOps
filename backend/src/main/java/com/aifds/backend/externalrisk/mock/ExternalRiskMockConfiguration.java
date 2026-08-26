package com.aifds.backend.externalrisk.mock;

import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.service.ExternalRiskLookupCommandReader;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile(ExternalRiskMockConfiguration.MOCK_PROFILE)
@ConditionalOnProperty(
        prefix = ExternalRiskMockConfiguration.PROPERTY_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
@EnableConfigurationProperties(ExternalRiskMockProperties.class)
public class ExternalRiskMockConfiguration {

    public static final String MOCK_PROFILE = "external-risk-mock";
    public static final String PROPERTY_PREFIX = "finguardops.external-risk.mock";

    private static final Profiles PRODUCTION_PROFILES =
            Profiles.of("production", "prod");
    private static final Profiles APPROVED_ENVIRONMENT_PROFILES =
            Profiles.of("local", "dev", "test");

    public ExternalRiskMockConfiguration(Environment environment) {
        if (environment.acceptsProfiles(PRODUCTION_PROFILES)) {
            throw new IllegalStateException(
                    "External Risk Mock is forbidden in production"
            );
        }
        if (!environment.acceptsProfiles(APPROVED_ENVIRONMENT_PROFILES)) {
            throw new IllegalStateException(
                    "External Risk Mock requires a local, dev, or test profile"
            );
        }
    }

    @Bean
    ExternalRiskMockAdapter externalRiskMockAdapter(
            ExternalRiskMockProperties properties
    ) {
        if (properties.scenario() == null) {
            throw new IllegalStateException(
                    "External Risk Mock scenario is required"
            );
        }
        return new ExternalRiskMockAdapter(properties.scenario());
    }

    @Bean
    ExternalRiskPolicyService externalRiskPolicyService(
            ExternalRiskMockAdapter adapter,
            Clock clock
    ) {
        return new ExternalRiskPolicyService(adapter, clock);
    }

    @Bean
    ExternalRiskRuleAnalysisCoordinator externalRiskRuleAnalysisCoordinator(
            ExternalRiskLookupCommandReader commandReader,
            ExternalRiskPolicyService policyService,
            RuleAnalysisOrchestrationService ruleAnalysisOrchestrationService
    ) {
        return new ExternalRiskRuleAnalysisCoordinator(
                commandReader,
                policyService,
                ruleAnalysisOrchestrationService
        );
    }
}
