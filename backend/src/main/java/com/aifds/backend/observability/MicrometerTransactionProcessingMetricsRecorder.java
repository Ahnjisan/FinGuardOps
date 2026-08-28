package com.aifds.backend.observability;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public final class MicrometerTransactionProcessingMetricsRecorder
        implements TransactionProcessingMetricsRecorder {

    public static final String SERVICE = "spring-backend";
    public static final String INTAKE_OUTCOMES =
            "finguardops.transaction.intake.outcomes";
    public static final String TRANSACTIONS_RECEIVED =
            "finguardops.transactions.received";
    public static final String TRANSACTION_OUTCOMES =
            "finguardops.transaction.outcomes";
    public static final String TRANSACTION_PROCESSING_DURATION =
            "finguardops.transaction.processing.duration";
    public static final String DUPLICATE_REQUESTS =
            "finguardops.http.duplicate.requests";
    public static final String IDEMPOTENCY_CONFLICTS =
            "finguardops.http.idempotency.conflicts";
    public static final String EXTERNAL_RISK_OUTCOMES =
            "finguardops.external.risk.outcomes";
    public static final String EXTERNAL_RISK_DURATION =
            "finguardops.external.risk.duration";
    public static final String RULE_ANALYSIS_OUTCOMES =
            "finguardops.rule.analysis.outcomes";
    public static final String RULE_ANALYSIS_DURATION =
            "finguardops.rule.analysis.duration";

    private final MeterRegistry meterRegistry;

    @Autowired
    public MicrometerTransactionProcessingMetricsRecorder(
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this(safeRegistry(meterRegistryProvider));
    }

    MicrometerTransactionProcessingMetricsRecorder(
            MeterRegistry meterRegistry
    ) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordIntakeOutcome(IntakeOutcome outcome) {
        if (outcome == null) {
            return;
        }
        counter(
                INTAKE_OUTCOMES,
                "Public transaction intake outcomes",
                Tags.of("service", SERVICE, "result", outcome.tagValue())
        );
    }

    @Override
    public void recordTransactionReceived() {
        counter(
                TRANSACTIONS_RECEIVED,
                "Transactions first committed in RECEIVED status",
                Tags.of("service", SERVICE, "result", "received")
        );
    }

    @Override
    public void recordTransactionTerminal(
            TransactionProcessingStatus status,
            RiskLevel riskLevel,
            FailureCategory failureCategory,
            Duration processingDuration
    ) {
        if (!isTerminal(status)) {
            return;
        }
        FailureCategory category = terminalFailureCategory(
                status,
                failureCategory
        );
        Tags tags = Tags.of(
                "service", SERVICE,
                "status", status.name(),
                "riskLevel", riskLevel == null ? "unknown" : riskLevel.name(),
                "failureCategory", category.tagValue()
        );
        counter(
                TRANSACTION_OUTCOMES,
                "First committed terminal transaction outcomes",
                tags
        );
        timer(
                TRANSACTION_PROCESSING_DURATION,
                "DB-authoritative transaction processing duration",
                tags,
                processingDuration
        );
    }

    @Override
    public void recordDuplicateRequest(DuplicateResult result) {
        if (result == null) {
            return;
        }
        counter(
                DUPLICATE_REQUESTS,
                "Same-key same-fingerprint transaction requests",
                Tags.of("service", SERVICE, "result", result.tagValue())
        );
    }

    @Override
    public void recordIdempotencyConflict() {
        counter(
                IDEMPOTENCY_CONFLICTS,
                "Same-key different-fingerprint transaction requests",
                Tags.of("service", SERVICE, "result", "conflict")
        );
    }

    @Override
    public void recordExternalRisk(
            ExternalRiskResult result,
            FailureCategory failureCategory,
            Duration duration
    ) {
        if (result == null) {
            return;
        }
        FailureCategory category = result == ExternalRiskResult.FAILED
                ? failureOrUnknown(failureCategory)
                : FailureCategory.NONE;
        Tags tags = Tags.of(
                "service", SERVICE,
                "result", result.tagValue(),
                "failureCategory", category.tagValue()
        );
        counter(
                EXTERNAL_RISK_OUTCOMES,
                "External Risk policy and provider outcomes",
                tags
        );
        timer(
                EXTERNAL_RISK_DURATION,
                "External Risk policy and provider duration",
                tags,
                duration
        );
    }

    @Override
    public void recordRuleAnalysis(
            RuleResult result,
            RiskLevel riskLevel,
            FailureCategory failureCategory,
            Duration duration
    ) {
        if (result == null) {
            return;
        }
        FailureCategory category = result == RuleResult.FAILED
                ? failureOrUnknown(failureCategory)
                : FailureCategory.NONE;
        Tags tags = Tags.of(
                "service", SERVICE,
                "result", result.tagValue(),
                "riskLevel", riskLevel == null ? "unknown" : riskLevel.name(),
                "failureCategory", category.tagValue()
        );
        counter(
                RULE_ANALYSIS_OUTCOMES,
                "Spring Rule analysis orchestration outcomes",
                tags
        );
        timer(
                RULE_ANALYSIS_DURATION,
                "Spring Rule analysis orchestration duration",
                tags,
                duration
        );
    }

    private void counter(
            String name,
            String description,
            Tags tags
    ) {
        safely(() -> Counter.builder(name)
                .description(description)
                .tags(tags)
                .register(meterRegistry)
                .increment());
    }

    private void timer(
            String name,
            String description,
            Tags tags,
            Duration duration
    ) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        safely(() -> Timer.builder(name)
                .description(description)
                .tags(tags)
                .register(meterRegistry)
                .record(duration));
    }

    private void safely(Runnable operation) {
        if (meterRegistry == null) {
            return;
        }
        try {
            operation.run();
        } catch (Throwable ignored) {
            // Metrics are deliberately isolated from business processing.
        }
    }

    private FailureCategory terminalFailureCategory(
            TransactionProcessingStatus status,
            FailureCategory failureCategory
    ) {
        if (status != TransactionProcessingStatus.FAILED) {
            return FailureCategory.NONE;
        }
        return failureOrUnknown(failureCategory);
    }

    private FailureCategory failureOrUnknown(
            FailureCategory failureCategory
    ) {
        if (failureCategory == null || failureCategory == FailureCategory.NONE) {
            return FailureCategory.UNKNOWN;
        }
        return failureCategory;
    }

    private boolean isTerminal(TransactionProcessingStatus status) {
        return status == TransactionProcessingStatus.APPROVED
                || status
                == TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                || status == TransactionProcessingStatus.HELD
                || status == TransactionProcessingStatus.FAILED;
    }

    private static MeterRegistry safeRegistry(
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        if (meterRegistryProvider == null) {
            return null;
        }
        try {
            return meterRegistryProvider.getIfAvailable();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
