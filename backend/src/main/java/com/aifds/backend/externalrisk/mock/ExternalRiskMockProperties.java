package com.aifds.backend.externalrisk.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = ExternalRiskMockConfiguration.PROPERTY_PREFIX)
public record ExternalRiskMockProperties(ExternalRiskMockScenario scenario) {
}
