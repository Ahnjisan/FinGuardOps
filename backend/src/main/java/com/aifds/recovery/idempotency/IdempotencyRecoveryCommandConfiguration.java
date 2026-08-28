package com.aifds.recovery.idempotency;

import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.repository.JpaAuditLogRepository;
import com.aifds.backend.common.time.PostgresqlTransactionTimestampProvider;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.fingerprint.TransactionRequestFingerprint;
import com.aifds.backend.idempotency.repository.IdempotencyRecoveryAuditLogRepository;
import com.aifds.backend.idempotency.service.IdempotencyClaimWriter;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryAuditWriter;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryService;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryTransaction;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.service.TransactionIntakeSnapshotCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {
        AuditLog.class,
        DetectionEvidence.class,
        CaseTransaction.class,
        IdempotencyRecord.class,
        RuleVersion.class,
        FinancialTransaction.class
})
@EnableJpaRepositories(basePackages = {
        "com.aifds.backend.idempotency.repository",
        "com.aifds.backend.transaction.repository",
        "com.aifds.backend.detection.repository",
        "com.aifds.backend.fraudcase.repository"
}, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
                DetectionResultRepository.class,
                FraudCaseRepository.class
        }
))
@ComponentScan(
        basePackageClasses = TransactionIntakeSnapshotCodec.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.aifds\\.backend\\.transaction\\.service\\."
                        + "Transaction.*Snapshot.*Codec"
        )
)
@Import({
        JpaAuditLogRepository.class,
        PostgresqlTransactionTimestampProvider.class,
        TransactionRequestFingerprint.class,
        IdempotencyRecoveryAuditLogRepository.class,
        IdempotencyClaimWriter.class,
        IdempotencyService.class,
        IdempotencyRecoveryAuditWriter.class,
        IdempotencyRecoveryTransaction.class,
        IdempotencyRecoveryService.class
})
public class IdempotencyRecoveryCommandConfiguration {

    @Bean
    IdempotencyRecoveryCommandRunner idempotencyRecoveryCommandRunner(
            IdempotencyRecoveryService recoveryService,
            ObjectMapper objectMapper
    ) {
        return new IdempotencyRecoveryCommandRunner(
                recoveryService,
                objectMapper
        );
    }
}
