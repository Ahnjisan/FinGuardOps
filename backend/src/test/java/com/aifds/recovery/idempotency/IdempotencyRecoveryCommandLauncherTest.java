package com.aifds.recovery.idempotency;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyRecoveryCommandLauncherTest {

    private static final String STARTUP_FAILURE_SECRET =
            "unique-refresh-secret password=credential";

    @Test
    void invalidCommandCreatesNoContextAndEmitsOnlySafeFixedCode() {
        AtomicInteger contextStarts = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IdempotencyRecoveryCommandLauncher launcher = launcher(
                () -> {
                    contextStarts.incrementAndGet();
                    return mock(ConfigurableApplicationContext.class);
                },
                output,
                error
        );

        int exitCode = launcher.launch(new String[]{
                argument("enabled=true"),
                argument("action=recover"),
                argument("record-id=password=secret")
        });

        assertThat(exitCode).isEqualTo(2);
        assertThat(contextStarts).hasValue(0);
        assertThat(utf8(output)).isEmpty();
        assertThat(utf8(error)).isEqualTo(
                "{\"type\":\"error\",\"code\":"
                        + "\"INVALID_RECOVERY_COMMAND\"}"
                        + System.lineSeparator()
        ).doesNotContain("password", "secret");
    }

    @Test
    void validCommandGetsOrdinaryRunnerCallsItOnceAndClosesContext() {
        ConfigurableApplicationContext context = mock(
                ConfigurableApplicationContext.class
        );
        IdempotencyRecoveryCommandRunner runner = mock(
                IdempotencyRecoveryCommandRunner.class
        );
        when(context.getBean(IdempotencyRecoveryCommandRunner.class))
                .thenReturn(runner);
        when(runner.run(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IdempotencyRecoveryCommandResult(
                        0,
                        List.of("{\"type\":\"summary\"}")
                ));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IdempotencyRecoveryCommandLauncher launcher = launcher(
                () -> context,
                output,
                error
        );

        int exitCode = launcher.launch(inspectArguments());

        assertThat(exitCode).isZero();
        assertThat(utf8(output)).isEqualTo(
                "{\"type\":\"summary\"}" + System.lineSeparator()
        );
        assertThat(utf8(error)).isEmpty();
        verify(context, times(1)).getBean(
                IdempotencyRecoveryCommandRunner.class
        );
        verify(runner, times(1)).run(
                org.mockito.ArgumentMatchers.any()
        );
        verify(context, times(1)).close();
    }

    @Test
    void contextStartupFailureEmitsNoExceptionDetail() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IdempotencyRecoveryCommandLauncher launcher = launcher(
                () -> {
                    throw new IllegalStateException(
                            "jdbc:postgresql://secret password=credential"
                    );
                },
                output,
                error
        );

        int exitCode = launcher.launch(inspectArguments());

        assertThat(exitCode).isEqualTo(1);
        assertThat(utf8(output)).isEmpty();
        assertThat(utf8(error)).isEqualTo(
                "{\"type\":\"error\",\"code\":"
                        + "\"RECOVERY_INTERNAL_FAILURE\"}"
                        + System.lineSeparator()
        ).doesNotContain("jdbc", "password", "credential");
    }

    @Test
    void actualSpringRefreshFailureIsIsolatedFromHostileLoggingAndRestored() {
        String loggingLevelProperty = "logging.level.root";
        String applicationJsonProperty = "spring.application.json";
        String originalLoggingLevel = System.getProperty(loggingLevelProperty);
        String originalApplicationJson = System.getProperty(
                applicationJsonProperty
        );
        String originalLoggingSystem = System.getProperty(
                LoggingSystem.SYSTEM_PROPERTY
        );
        PrintStream originalOutput = System.out;
        PrintStream originalError = System.err;
        Logger rootLogger = (Logger) LoggerFactory.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME
        );
        Level originalRootLevel = rootLogger.getLevel();
        ListAppender<ILoggingEvent> capturedEvents = new ListAppender<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        try {
            System.setProperty(loggingLevelProperty, "TRACE");
            System.setProperty(
                    applicationJsonProperty,
                    "{\"logging\":{\"level\":{\"root\":\"TRACE\"}}}"
            );
            System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
            capturedEvents.start();
            rootLogger.addAppender(capturedEvents);
            System.setOut(new PrintStream(
                    output,
                    true,
                    StandardCharsets.UTF_8
            ));
            System.setErr(new PrintStream(
                    error,
                    true,
                    StandardCharsets.UTF_8
            ));
            IdempotencyRecoveryCommandLauncher launcher = launcher(
                    this::startActuallyFailingSpringContext,
                    output,
                    error
            );

            int exitCode = launcher.launch(inspectArguments());

            assertThat(exitCode).isEqualTo(1);
            assertThat(utf8(output)).isEmpty();
            assertThat(utf8(error)).isEqualTo(
                    "{\"type\":\"error\",\"code\":"
                            + "\"RECOVERY_INTERNAL_FAILURE\"}"
                            + System.lineSeparator()
            ).doesNotContain(
                    STARTUP_FAILURE_SECRET,
                    IllegalStateException.class.getName(),
                    "stack trace",
                    "APPLICATION FAILED TO START",
                    "password",
                    "credential"
            );
            assertThat(capturedEvents.list).isEmpty();
            assertThat(rootLogger.getLevel()).isEqualTo(originalRootLevel);
            assertThat(System.getProperty(LoggingSystem.SYSTEM_PROPERTY))
                    .isNull();
            assertThat(System.getProperty(loggingLevelProperty))
                    .isEqualTo("TRACE");
            assertThat(System.getProperty(applicationJsonProperty))
                    .isEqualTo(
                            "{\"logging\":{\"level\":{\"root\":\"TRACE\"}}}"
                    );
        } finally {
            System.setOut(originalOutput);
            System.setErr(originalError);
            rootLogger.detachAppender(capturedEvents);
            capturedEvents.stop();
            rootLogger.setLevel(originalRootLevel);
            restoreProperty(loggingLevelProperty, originalLoggingLevel);
            restoreProperty(applicationJsonProperty, originalApplicationJson);
            restoreProperty(
                    LoggingSystem.SYSTEM_PROPERTY,
                    originalLoggingSystem
            );
        }
    }

    @Test
    void runnerFailureIsNotRetriedAndContextIsClosed() {
        ConfigurableApplicationContext context = mock(
                ConfigurableApplicationContext.class
        );
        IdempotencyRecoveryCommandRunner runner = mock(
                IdempotencyRecoveryCommandRunner.class
        );
        when(context.getBean(IdempotencyRecoveryCommandRunner.class))
                .thenReturn(runner);
        when(runner.run(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("stack trace secret"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        IdempotencyRecoveryCommandLauncher launcher = launcher(
                () -> context,
                output,
                error
        );

        int exitCode = launcher.launch(inspectArguments());

        assertThat(exitCode).isEqualTo(1);
        assertThat(utf8(output)).isEmpty();
        assertThat(utf8(error))
                .contains("RECOVERY_INTERNAL_FAILURE")
                .doesNotContain("stack trace", "secret");
        verify(runner, times(1)).run(
                org.mockito.ArgumentMatchers.any()
        );
        verify(context, times(1)).close();
    }

    private IdempotencyRecoveryCommandLauncher launcher(
            IdempotencyRecoveryCommandLauncher.RecoveryContextFactory factory,
            ByteArrayOutputStream output,
            ByteArrayOutputStream error
    ) {
        return new IdempotencyRecoveryCommandLauncher(
                factory,
                output,
                error
        );
    }

    private ConfigurableApplicationContext startActuallyFailingSpringContext() {
        SpringApplication application = new SpringApplication(
                FailingRefreshConfiguration.class
        );
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        return application.run();
    }

    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private String[] inspectArguments() {
        return new String[]{
                argument("enabled=true"),
                argument("action=inspect")
        };
    }

    private String argument(String option) {
        return IdempotencyRecoveryCommandArguments.PREFIX + option;
    }

    private String utf8(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingRefreshConfiguration {

        @Bean
        Object failsDuringRefresh() {
            throw new IllegalStateException(STARTUP_FAILURE_SECRET);
        }
    }
}
