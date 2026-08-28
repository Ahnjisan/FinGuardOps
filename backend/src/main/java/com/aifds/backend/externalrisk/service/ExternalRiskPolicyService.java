package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskContracts;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.port.ExternalRiskLookupPort;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public final class ExternalRiskPolicyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ExternalRiskPolicyService.class
    );

    private final ExternalRiskLookupPort lookupPort;
    private final Clock clock;
    private volatile TransactionProcessingMetricsRecorder metricsRecorder;

    public ExternalRiskPolicyService(
            ExternalRiskLookupPort lookupPort,
            Clock clock
    ) {
        this(
                lookupPort,
                clock,
                TransactionProcessingMetricsRecorder.noop()
        );
    }

    public ExternalRiskPolicyService(
            ExternalRiskLookupPort lookupPort,
            Clock clock,
            TransactionProcessingMetricsRecorder metricsRecorder
    ) {
        this.lookupPort = Objects.requireNonNull(
                lookupPort,
                "lookupPort must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.metricsRecorder = metricsRecorder == null
                ? TransactionProcessingMetricsRecorder.noop()
                : metricsRecorder;
    }

    @Autowired(required = false)
    void setMetricsRecorder(
            TransactionProcessingMetricsRecorder metricsRecorder
    ) {
        if (metricsRecorder != null) {
            this.metricsRecorder = metricsRecorder;
        }
    }

    public ExternalRiskSnapshot lookup(ExternalRiskLookupCommand command) {
        long startedAt = System.nanoTime();
        try {
            if (command == null) {
                throw new ExternalRiskLookupException(
                        ExternalRiskFailureCategory.INVALID_REQUEST
                );
            }
            command.validate();
            ExternalRiskProviderRequest request = toProviderRequest(command);
            ExternalRiskProviderResponse response = lookupPort.lookup(request);
            ValidatedResponse validated = validateResponse(response);
            Instant lookedUpAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (validated.providerAsOf().isAfter(lookedUpAt)) {
                throw invalidResponse();
            }

            ExternalRiskPolicyResult policyResult = validated.matches().isEmpty()
                    ? ExternalRiskPolicyResult.UNMATCHED
                    : ExternalRiskPolicyResult.MATCHED;
            ExternalRiskSnapshot snapshot = toSnapshot(
                    command,
                    validated,
                    lookedUpAt,
                    policyResult
            );
            LOGGER.info(
                    "event=external_risk_lookup traceId={} providerCode={} "
                            + "lookupStatus={} policyResult={} matchCount={}",
                    command.traceId(),
                    snapshot.providerCode(),
                    snapshot.lookupStatus(),
                    snapshot.policyResult(),
                    snapshot.matches().size()
            );
            record(
                    policyResult == ExternalRiskPolicyResult.MATCHED
                            ? TransactionProcessingMetricsRecorder
                            .ExternalRiskResult.MATCHED
                            : TransactionProcessingMetricsRecorder
                            .ExternalRiskResult.UNMATCHED,
                    TransactionProcessingMetricsRecorder.FailureCategory.NONE,
                    startedAt
            );
            return snapshot;
        } catch (ExternalRiskLookupException exception) {
            LOGGER.warn(
                    "event=external_risk_lookup traceId={} failureCategory={}",
                    command == null ? null : command.traceId(),
                    exception.category()
            );
            record(
                    TransactionProcessingMetricsRecorder.ExternalRiskResult
                            .FAILED,
                    TransactionProcessingMetricsRecorder.FailureCategory
                            .fromExternalRisk(exception.category()),
                    startedAt
            );
            throw exception;
        } catch (RuntimeException exception) {
            record(
                    TransactionProcessingMetricsRecorder.ExternalRiskResult
                            .FAILED,
                    TransactionProcessingMetricsRecorder.FailureCategory
                            .UNKNOWN,
                    startedAt
            );
            throw exception;
        }
    }

    private void record(
            TransactionProcessingMetricsRecorder.ExternalRiskResult result,
            TransactionProcessingMetricsRecorder.FailureCategory category,
            long startedAt
    ) {
        try {
            long elapsed = System.nanoTime() - startedAt;
            metricsRecorder.recordExternalRisk(
                    result,
                    category,
                    Duration.ofNanos(Math.max(0L, elapsed))
            );
        } catch (Throwable ignored) {
            // Metrics cannot replace policy or provider results.
        }
    }

    private ExternalRiskProviderRequest toProviderRequest(
            ExternalRiskLookupCommand command
    ) {
        return new ExternalRiskProviderRequest(
                command.transactionType(),
                command.evaluationCutoffAt(),
                command.externalCustomerRef(),
                command.senderAccountRef(),
                command.recipientAccountRef(),
                command.deviceRef(),
                command.traceId()
        );
    }

    private ValidatedResponse validateResponse(
            ExternalRiskProviderResponse response
    ) {
        if (response == null
                || !ExternalRiskContracts.isProviderCode(response.providerCode())
                || response.providerAsOf() == null
                || response.providerAsOf().getNano() % 1_000 != 0
                || !ExternalRiskContracts.hasValidUniqueMatches(
                    response.matches()
                )) {
            throw invalidResponse();
        }
        return new ValidatedResponse(
                response.providerCode(),
                response.providerAsOf(),
                List.copyOf(response.matches())
        );
    }

    private ExternalRiskSnapshot toSnapshot(
            ExternalRiskLookupCommand command,
            ValidatedResponse response,
            Instant lookedUpAt,
            ExternalRiskPolicyResult policyResult
    ) {
        try {
            return new ExternalRiskSnapshot(
                    command.transactionId(),
                    command.evaluationCutoffAt(),
                    lookedUpAt,
                    response.providerCode(),
                    response.providerAsOf(),
                    ExternalRiskLookupStatus.SUCCEEDED,
                    policyResult,
                    response.matches()
            );
        } catch (IllegalArgumentException exception) {
            throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.TRANSFORMATION_ERROR,
                    exception
            );
        }
    }

    private ExternalRiskLookupException invalidResponse() {
        return new ExternalRiskLookupException(
                ExternalRiskFailureCategory.INVALID_RESPONSE
        );
    }

    private record ValidatedResponse(
            String providerCode,
            Instant providerAsOf,
            List<ExternalRiskMatch> matches
    ) {
    }
}
