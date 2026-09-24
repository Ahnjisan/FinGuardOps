package com.aifds.backend;

import com.aifds.backend.rule.operation.RuleV1DefaultRuleSetPublicationDiagnosticBoundary;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandArguments;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.function.Supplier;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        if (IdempotencyRecoveryCommandArguments.hasRecoveryPrefix(args)) {
            int exitCode = new IdempotencyRecoveryCommandLauncher()
                    .launch(args);
            System.exit(exitCode);
            return;
        }
        RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary =
                new RuleV1DefaultRuleSetPublicationDiagnosticBoundary();
        SpringApplication application = new SpringApplication(
                BackendApplication.class
        );
        application.addInitializers(publicationDiagnosticInitializer(boundary));
        runWithPublicationDiagnosticBoundary(
                () -> application.run(args),
                boundary
        );
    }

    static ApplicationContextInitializer<ConfigurableApplicationContext>
    publicationDiagnosticInitializer(
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary
    ) {
        return context -> {
            try {
                context.getBeanFactory().registerSingleton(
                        RuleV1DefaultRuleSetPublicationDiagnosticBoundary.class
                                .getName(),
                        boundary
                );
                boundary.tryArm(context.getEnvironment());
            } catch (Throwable ignored) {
                // Diagnostics are fail-closed and cannot create startup failure.
            }
        };
    }

    static ConfigurableApplicationContext runWithPublicationDiagnosticBoundary(
            Supplier<ConfigurableApplicationContext> runner,
            RuleV1DefaultRuleSetPublicationDiagnosticBoundary boundary
    ) {
        try {
            return runner.get();
        } catch (RuntimeException | Error failure) {
            boundary.emitStartupFailure(failure);
            throw failure;
        }
    }
}
