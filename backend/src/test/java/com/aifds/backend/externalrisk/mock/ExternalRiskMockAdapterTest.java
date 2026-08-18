package com.aifds.backend.externalrisk.mock;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalRiskMockAdapterTest {

    private static final Instant CUTOFF = Instant.parse(
            "2026-08-17T04:00:00.123456Z"
    );

    @Test
    void everySuccessfulScenarioReturnsItsExactDeterministicResult() {
        assertScenario(
                ExternalRiskMockScenario.MATCHED_SENDER_ACCOUNT,
                List.of(match(
                        ExternalRiskSubjectType.SENDER_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
                ))
        );
        assertScenario(
                ExternalRiskMockScenario.MATCHED_RECIPIENT_ACCOUNT,
                List.of(match(
                        ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT
                ))
        );
        assertScenario(
                ExternalRiskMockScenario.MATCHED_DEVICE,
                List.of(match(
                        ExternalRiskSubjectType.DEVICE,
                        ExternalRiskType.RISK_DEVICE,
                        ExternalRiskReasonCode.RISK_DEVICE
                ))
        );
        assertScenario(ExternalRiskMockScenario.UNMATCHED, List.of());
    }

    @Test
    void invalidResponseScenarioReturnsContradictionForPolicyValidation() {
        ExternalRiskMockAdapter adapter = adapter(
                ExternalRiskMockScenario.INVALID_RESPONSE
        );
        ExternalRiskProviderRequest request = request(
                "recipient-ref",
                "device-ref"
        );
        ExternalRiskProviderResponse response = adapter.lookup(request);

        assertThat(response.matches()).containsExactly(match(
                ExternalRiskSubjectType.SENDER_ACCOUNT,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        ));
        assertThat(adapter.lookup(request)).isEqualTo(response);
        assertThatThrownBy(() -> new ExternalRiskPolicyService(
                adapter,
                Clock.fixed(CUTOFF.plusSeconds(1), ZoneOffset.UTC)
        ).lookup(new ExternalRiskLookupCommand(
                UUID.fromString(
                        "54000000-0000-4000-8000-000000000001"
                ),
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                "recipient-ref",
                "device-ref",
                "trace-ext-risk-0001"
        ))).isInstanceOfSatisfying(
                ExternalRiskLookupException.class,
                exception -> assertThat(exception.category()).isEqualTo(
                        ExternalRiskFailureCategory.INVALID_RESPONSE
                )
        );
    }

    @Test
    void missingScenarioCapabilityFailsInsteadOfFallingBackToUnmatched() {
        assertFailure(
                ExternalRiskMockScenario.MATCHED_RECIPIENT_ACCOUNT,
                request(null, "device-ref"),
                ExternalRiskFailureCategory.UNSUPPORTED_CAPABILITY
        );
        assertFailure(
                ExternalRiskMockScenario.MATCHED_DEVICE,
                request("recipient-ref", null),
                ExternalRiskFailureCategory.UNSUPPORTED_CAPABILITY
        );
    }

    @Test
    void timeoutAndUnavailableAreTypedAndNotConvertedToFallback() {
        assertFailure(
                ExternalRiskMockScenario.TIMEOUT,
                request("recipient-ref", "device-ref"),
                ExternalRiskFailureCategory.TIMEOUT
        );
        assertFailure(
                ExternalRiskMockScenario.UNAVAILABLE,
                request("recipient-ref", "device-ref"),
                ExternalRiskFailureCategory.UNAVAILABLE
        );
        assertFailure(
                ExternalRiskMockScenario.TIMEOUT,
                request("recipient-ref", "device-ref"),
                ExternalRiskFailureCategory.TIMEOUT
        );
        assertFailure(
                ExternalRiskMockScenario.UNAVAILABLE,
                request("recipient-ref", "device-ref"),
                ExternalRiskFailureCategory.UNAVAILABLE
        );
    }

    private void assertScenario(
            ExternalRiskMockScenario scenario,
            List<ExternalRiskMatch> expectedMatches
    ) {
        ExternalRiskMockAdapter adapter = adapter(scenario);
        ExternalRiskProviderRequest request = request(
                "recipient-ref",
                "device-ref"
        );

        ExternalRiskProviderResponse first = adapter.lookup(request);
        ExternalRiskProviderResponse second = adapter.lookup(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.providerCode()).isEqualTo(
                "EXTERNAL_RISK_MOCK_V1"
        );
        assertThat(first.providerAsOf()).isEqualTo(CUTOFF);
        assertThat(first.matches()).containsExactlyElementsOf(expectedMatches);
    }

    private void assertFailure(
            ExternalRiskMockScenario scenario,
            ExternalRiskProviderRequest request,
            ExternalRiskFailureCategory expected
    ) {
        assertThatThrownBy(() -> adapter(scenario).lookup(request))
                .isInstanceOfSatisfying(
                        ExternalRiskLookupException.class,
                        exception -> assertThat(exception.category()).isEqualTo(
                                expected
                        )
                );
    }

    private ExternalRiskMockAdapter adapter(ExternalRiskMockScenario scenario) {
        return new ExternalRiskMockAdapter(scenario);
    }

    private ExternalRiskProviderRequest request(
            String recipientAccountRef,
            String deviceRef
    ) {
        return new ExternalRiskProviderRequest(
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                recipientAccountRef,
                deviceRef,
                "trace-ext-risk-0001"
        );
    }

    private ExternalRiskMatch match(
            ExternalRiskSubjectType subjectType,
            ExternalRiskType riskType,
            ExternalRiskReasonCode reasonCode
    ) {
        return new ExternalRiskMatch(subjectType, riskType, reasonCode);
    }
}
