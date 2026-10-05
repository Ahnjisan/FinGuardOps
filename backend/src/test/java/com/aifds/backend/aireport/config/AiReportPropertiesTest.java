package com.aifds.backend.aireport.config;

import com.aifds.backend.aireport.service.AiReportWorker;
import com.aifds.backend.rule.operation.RuleV1DefaultRuleSetPublicationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiReportPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiReportProperties.class, AiReportWorker.class)
            .withPropertyValues(
                    "finguardops.ai-report.base-url=http://127.0.0.1:8000",
                    "finguardops.ai-report.poll-interval-ms=1000",
                    "finguardops.ai-report.lease-seconds=300"
            );

    @Test
    void regularProfilesKeepTheWorkerScheduledAndPropertiesBound() {
        for (String profile : new String[]{"local", "dev", "test", "production"}) {
            runner.withInitializer(context -> context.getEnvironment()
                    .setActiveProfiles(profile)).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AiReportProperties.Values.class))
                        .extracting(AiReportProperties.Values::pollIntervalMs,
                                AiReportProperties.Values::leaseSeconds)
                        .containsExactly(1000L, 300L);
                ScheduledAnnotationBeanPostProcessor scheduler = context.getBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                        ScheduledAnnotationBeanPostProcessor.class
                );
                assertThat(scheduler.getScheduledTasks()).hasSize(1);
                assertThat(context).hasSingleBean(AiReportWorker.class);
            });
        }
    }

    @Test
    void publicationProfileKeepsPropertiesAndWorkerButDoesNotScheduleIt() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(
                "local", RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE
        )).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiReportProperties.Values.class);
            assertThat(context).hasSingleBean(AiReportWorker.class);
            assertThat(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
            )).isFalse();
        });
    }

    @Test
    void invalidWorkerPropertiesStillFailBinding() {
        runner.withPropertyValues("finguardops.ai-report.lease-seconds=299")
                .run(context -> assertThat(context).hasFailed());
    }
}
