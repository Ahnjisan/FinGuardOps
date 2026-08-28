package com.aifds.recovery.idempotency;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class IdempotencyRecoveryCommandLauncher {

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_INTERNAL_FAILURE = 1;
    public static final int EXIT_INVALID_COMMAND = 2;
    public static final int EXIT_REJECTED = 3;

    private static final String INVALID_COMMAND_OUTPUT =
            "{\"type\":\"error\",\"code\":\"INVALID_RECOVERY_COMMAND\"}";
    private static final String INTERNAL_FAILURE_OUTPUT =
            "{\"type\":\"error\",\"code\":\"RECOVERY_INTERNAL_FAILURE\"}";
    private static final Object LOGGING_STATE_MONITOR = new Object();

    private static int activeLoggingIsolations;
    private static String previousLoggingSystemProperty;
    private static Logger isolatedRootLogger;
    private static Level previousRootLevel;

    private final RecoveryContextFactory contextFactory;
    private final PrintWriter standardOutput;
    private final PrintWriter standardError;

    public IdempotencyRecoveryCommandLauncher() {
        this(
                IdempotencyRecoveryCommandLauncher::startRecoveryContext,
                System.out,
                System.err
        );
    }

    public IdempotencyRecoveryCommandLauncher(
            RecoveryContextFactory contextFactory,
            OutputStream standardOutput,
            OutputStream standardError
    ) {
        this.contextFactory = contextFactory;
        this.standardOutput = utf8Writer(standardOutput);
        this.standardError = utf8Writer(standardError);
    }

    public int launch(String[] args) {
        try (RecoveryLoggingIsolation ignored = isolateFrameworkLogging()) {
            return launchWithIsolatedLogging(args);
        } catch (RuntimeException exception) {
            writeInternalFailure();
            return EXIT_INTERNAL_FAILURE;
        }
    }

    private int launchWithIsolatedLogging(String[] args) {
        final IdempotencyRecoveryCommandArguments arguments;
        try {
            arguments = IdempotencyRecoveryCommandArguments.parse(args);
        } catch (RuntimeException exception) {
            standardError.println(INVALID_COMMAND_OUTPUT);
            standardError.flush();
            return EXIT_INVALID_COMMAND;
        }

        try (ConfigurableApplicationContext context = contextFactory.start()) {
            IdempotencyRecoveryCommandRunner runner = context.getBean(
                    IdempotencyRecoveryCommandRunner.class
            );
            IdempotencyRecoveryCommandResult result = runner.run(arguments);
            result.standardOutputLines().forEach(standardOutput::println);
            standardOutput.flush();
            return result.exitCode();
        } catch (RuntimeException exception) {
            writeInternalFailure();
            return EXIT_INTERNAL_FAILURE;
        }
    }

    private void writeInternalFailure() {
        standardError.println(INTERNAL_FAILURE_OUTPUT);
        standardError.flush();
    }

    private static ConfigurableApplicationContext startRecoveryContext() {
        SpringApplication application = new SpringApplication(
                IdempotencyRecoveryCommandConfiguration.class
        );
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setAddCommandLineProperties(false);
        application.setDefaultProperties(Map.of(
                "spring.main.banner-mode", "off",
                "spring.main.log-startup-info", "false"
        ));
        return application.run();
    }

    private static RecoveryLoggingIsolation isolateFrameworkLogging() {
        synchronized (LOGGING_STATE_MONITOR) {
            if (activeLoggingIsolations == 0) {
                previousLoggingSystemProperty = System.getProperty(
                        LoggingSystem.SYSTEM_PROPERTY
                );
                Logger rootLogger = requireLogbackRootLogger();
                Level rootLevel = rootLogger.getLevel();
                try {
                    System.setProperty(
                            LoggingSystem.SYSTEM_PROPERTY,
                            LoggingSystem.NONE
                    );
                    rootLogger.setLevel(Level.OFF);
                } catch (RuntimeException exception) {
                    restoreSystemProperty(previousLoggingSystemProperty);
                    throw exception;
                }
                isolatedRootLogger = rootLogger;
                previousRootLevel = rootLevel;
            }
            activeLoggingIsolations++;
            return new RecoveryLoggingIsolation();
        }
    }

    private static Logger requireLogbackRootLogger() {
        org.slf4j.Logger rootLogger = LoggerFactory.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME
        );
        if (rootLogger instanceof Logger logbackRootLogger) {
            return logbackRootLogger;
        }
        throw new IllegalStateException(
                "recovery logging isolation requires Logback"
        );
    }

    private static void restoreSystemProperty(String previousValue) {
        if (previousValue == null) {
            System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
        } else {
            System.setProperty(
                    LoggingSystem.SYSTEM_PROPERTY,
                    previousValue
            );
        }
    }

    private static PrintWriter utf8Writer(OutputStream outputStream) {
        return new PrintWriter(new OutputStreamWriter(
                outputStream,
                StandardCharsets.UTF_8
        ));
    }

    @FunctionalInterface
    public interface RecoveryContextFactory {
        ConfigurableApplicationContext start();
    }

    private static final class RecoveryLoggingIsolation
            implements AutoCloseable {

        private boolean closed;

        @Override
        public void close() {
            synchronized (LOGGING_STATE_MONITOR) {
                if (closed) {
                    return;
                }
                closed = true;
                activeLoggingIsolations--;
                if (activeLoggingIsolations == 0) {
                    restoreSystemProperty(previousLoggingSystemProperty);
                    isolatedRootLogger.setLevel(previousRootLevel);
                    previousLoggingSystemProperty = null;
                    isolatedRootLogger = null;
                    previousRootLevel = null;
                }
            }
        }
    }
}
