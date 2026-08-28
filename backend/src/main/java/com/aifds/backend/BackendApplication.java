package com.aifds.backend;

import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandArguments;
import com.aifds.recovery.idempotency.IdempotencyRecoveryCommandLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        if (IdempotencyRecoveryCommandArguments.hasRecoveryPrefix(args)) {
            int exitCode = new IdempotencyRecoveryCommandLauncher()
                    .launch(args);
            System.exit(exitCode);
            return;
        }
        SpringApplication.run(BackendApplication.class, args);
    }
}
