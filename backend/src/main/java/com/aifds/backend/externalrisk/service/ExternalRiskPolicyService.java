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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
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

    public ExternalRiskPolicyService(
            ExternalRiskLookupPort lookupPort,
            Clock clock
    ) {
        this.lookupPort = Objects.requireNonNull(
                lookupPort,
                "lookupPort must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ExternalRiskSnapshot lookup(ExternalRiskLookupCommand command) {
        if (command == null) {
            throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.INVALID_REQUEST
            );
        }
        command.validate();

        try {
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
            return snapshot;
        } catch (ExternalRiskLookupException exception) {
            LOGGER.warn(
                    "event=external_risk_lookup traceId={} failureCategory={}",
                    command.traceId(),
                    exception.category()
            );
            throw exception;
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
