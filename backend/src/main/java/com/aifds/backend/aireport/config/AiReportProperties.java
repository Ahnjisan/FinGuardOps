package com.aifds.backend.aireport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AiReportProperties.Values.class)
public class AiReportProperties {
    @ConfigurationProperties(prefix = "finguardops.ai-report")
    public record Values(String baseUrl, long pollIntervalMs, long leaseSeconds) {
        public Values {
            if (baseUrl == null || baseUrl.isBlank() || pollIntervalMs < 1 || leaseSeconds < 300) {
                throw new IllegalArgumentException("Invalid AI report worker configuration");
            }
        }
    }
}
