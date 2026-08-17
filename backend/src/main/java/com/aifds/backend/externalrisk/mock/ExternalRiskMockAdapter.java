package com.aifds.backend.externalrisk.mock;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.externalrisk.port.ExternalRiskLookupPort;

import java.util.List;
import java.util.Objects;

public final class ExternalRiskMockAdapter implements ExternalRiskLookupPort {

    public static final String PROVIDER_CODE = "EXTERNAL_RISK_MOCK_V1";

    private final ExternalRiskMockScenario scenario;

    public ExternalRiskMockAdapter(ExternalRiskMockScenario scenario) {
        this.scenario = Objects.requireNonNull(
                scenario,
                "scenario must not be null"
        );
    }

    @Override
    public ExternalRiskProviderResponse lookup(
            ExternalRiskProviderRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        return switch (scenario) {
            case MATCHED_SENDER_ACCOUNT -> response(
                    request,
                    new ExternalRiskMatch(
                            ExternalRiskSubjectType.SENDER_ACCOUNT,
                            ExternalRiskType.SUSPICIOUS_ACCOUNT,
                            ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
                    )
            );
            case MATCHED_RECIPIENT_ACCOUNT -> matchedRecipient(request);
            case MATCHED_DEVICE -> matchedDevice(request);
            case UNMATCHED -> response(request);
            case TIMEOUT -> throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.TIMEOUT
            );
            case UNAVAILABLE -> throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.UNAVAILABLE
            );
            case INVALID_RESPONSE -> response(
                    request,
                    new ExternalRiskMatch(
                            ExternalRiskSubjectType.SENDER_ACCOUNT,
                            ExternalRiskType.RISK_DEVICE,
                            ExternalRiskReasonCode.RISK_DEVICE
                    )
            );
        };
    }

    private ExternalRiskProviderResponse matchedRecipient(
            ExternalRiskProviderRequest request
    ) {
        if (request.recipientAccountRef() == null) {
            throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.UNSUPPORTED_CAPABILITY
            );
        }
        return response(
                request,
                new ExternalRiskMatch(
                        ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT
                )
        );
    }

    private ExternalRiskProviderResponse matchedDevice(
            ExternalRiskProviderRequest request
    ) {
        if (request.deviceRef() == null) {
            throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.UNSUPPORTED_CAPABILITY
            );
        }
        return response(
                request,
                new ExternalRiskMatch(
                        ExternalRiskSubjectType.DEVICE,
                        ExternalRiskType.RISK_DEVICE,
                        ExternalRiskReasonCode.RISK_DEVICE
                )
        );
    }

    private ExternalRiskProviderResponse response(
            ExternalRiskProviderRequest request,
            ExternalRiskMatch... matches
    ) {
        return new ExternalRiskProviderResponse(
                PROVIDER_CODE,
                request.evaluationCutoffAt(),
                List.of(matches)
        );
    }
}
