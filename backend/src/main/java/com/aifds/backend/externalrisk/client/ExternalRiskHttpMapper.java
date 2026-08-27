package com.aifds.backend.externalrisk.client;

import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpMatchResponse;
import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpRequest;
import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskContracts;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ExternalRiskHttpMapper {

    private final DomainResponseFactory responseFactory;

    public ExternalRiskHttpMapper() {
        this(ExternalRiskProviderResponse::new);
    }

    ExternalRiskHttpMapper(DomainResponseFactory responseFactory) {
        this.responseFactory = Objects.requireNonNull(
                responseFactory,
                "responseFactory must not be null"
        );
    }

    public ExternalRiskHttpRequest toHttpRequest(
            ExternalRiskProviderRequest request
    ) {
        if (request == null) {
            throw failure(ExternalRiskFailureCategory.INVALID_REQUEST);
        }
        return new ExternalRiskHttpRequest(
                request.transactionType(),
                request.evaluationCutoffAt(),
                request.externalCustomerRef(),
                request.senderAccountRef(),
                request.recipientAccountRef(),
                request.deviceRef(),
                request.traceId()
        );
    }

    public ExternalRiskProviderResponse toDomainResponse(
            ExternalRiskHttpResponse response
    ) {
        if (response == null
                || !ExternalRiskContracts.isProviderCode(response.providerCode())
                || response.providerAsOf() == null
                || response.providerAsOf().getNano() % 1_000 != 0
                || response.matches() == null
                || response.matches().size() > 3) {
            throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
        }

        List<ExternalRiskMatch> matches = new ArrayList<>(
                response.matches().size()
        );
        for (ExternalRiskHttpMatchResponse match : response.matches()) {
            if (match == null
                    || match.subjectType() == null
                    || match.riskType() == null
                    || match.reasonCode() == null) {
                throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
            }
            matches.add(new ExternalRiskMatch(
                    match.subjectType(),
                    match.riskType(),
                    match.reasonCode()
            ));
        }
        if (!ExternalRiskContracts.hasValidUniqueMatches(matches)) {
            throw failure(ExternalRiskFailureCategory.INVALID_RESPONSE);
        }

        try {
            return responseFactory.create(
                    response.providerCode(),
                    response.providerAsOf(),
                    matches
            );
        } catch (ExternalRiskLookupException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw failure(ExternalRiskFailureCategory.TRANSFORMATION_ERROR);
        }
    }

    private ExternalRiskLookupException failure(
            ExternalRiskFailureCategory category
    ) {
        return new ExternalRiskLookupException(category);
    }

    @FunctionalInterface
    interface DomainResponseFactory {

        ExternalRiskProviderResponse create(
                String providerCode,
                java.time.Instant providerAsOf,
                List<ExternalRiskMatch> matches
        );
    }
}
