package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupCommand;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupStatus;
import com.aifds.backend.externalrisk.domain.ExternalRiskMatch;
import com.aifds.backend.externalrisk.domain.ExternalRiskPolicyResult;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.externalrisk.port.ExternalRiskLookupPort;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ExternalRiskPolicyServiceTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "51000000-0000-4000-8000-000000000001"
    );
    private static final Instant CUTOFF = Instant.parse(
            "2026-08-17T03:00:00.123456Z"
    );
    private static final Instant CLOCK_INSTANT = Instant.parse(
            "2026-08-17T03:00:01.654321987Z"
    );
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final String TRACE_ID = "trace-ext-risk-0001";

    @Test
    void derivesMatchedResultForEverySupportedMatch() {
        for (ExternalRiskMatch match : List.of(
                senderMatch(),
                recipientMatch(),
                deviceMatch()
        )) {
            ExternalRiskLookupPort port = request -> response(List.of(match));
            ExternalRiskSnapshot snapshot = service(port).lookup(command());

            assertThat(snapshot.lookupStatus()).isEqualTo(
                    ExternalRiskLookupStatus.SUCCEEDED
            );
            assertThat(snapshot.policyResult()).isEqualTo(
                    ExternalRiskPolicyResult.MATCHED
            );
            assertThat(snapshot.matches()).containsExactly(match);
        }
    }

    @Test
    void derivesUnmatchedOnlyFromAnEmptyMatchList() {
        ExternalRiskSnapshot snapshot = service(
                request -> response(List.of())
        ).lookup(command());

        assertThat(snapshot.policyResult()).isEqualTo(
                ExternalRiskPolicyResult.UNMATCHED
        );
        assertThat(snapshot.matches()).isEmpty();
    }

    @Test
    void callsPortExactlyOnceWithNoInternalTransactionIdAndExactTrace() {
        ExternalRiskLookupPort port = mock(ExternalRiskLookupPort.class);
        when(port.lookup(any())).thenReturn(response(List.of(senderMatch())));

        service(port).lookup(command());

        var captor = org.mockito.ArgumentCaptor.forClass(
                ExternalRiskProviderRequest.class
        );
        verify(port).lookup(captor.capture());
        ExternalRiskProviderRequest request = captor.getValue();
        assertThat(request.traceId()).isEqualTo(TRACE_ID);
        assertThat(request.transactionType()).isEqualTo(
                TransactionType.ACCOUNT_TRANSFER
        );
        assertThat(request.evaluationCutoffAt()).isEqualTo(CUTOFF);
        assertThat(request.externalCustomerRef()).isEqualTo("customer-ref");
        assertThat(request.senderAccountRef()).isEqualTo("sender-ref");
        assertThat(request.recipientAccountRef()).isEqualTo("recipient-ref");
        assertThat(request.deviceRef()).isEqualTo("device-ref");
        assertThat(ExternalRiskProviderRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("transactionId");
    }

    @Test
    void rejectsNullIncompleteAndUnsupportedResponses() {
        assertInvalidResponse(null);
        assertInvalidResponse(new ExternalRiskProviderResponse(
                "",
                CUTOFF,
                List.of()
        ));
        assertInvalidResponse(new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                null,
                List.of()
        ));
        assertInvalidResponse(response(List.of(new ExternalRiskMatch(
                ExternalRiskSubjectType.SENDER_ACCOUNT,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        ))));
    }

    @Test
    void rejectsDuplicateMatches() {
        assertInvalidResponse(response(List.of(senderMatch(), senderMatch())));
    }

    @Test
    void validatesProviderCodeAsRestrictedAsciiBeforeLogging() {
        assertThat(service(request -> new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                List.of()
        )).lookup(command()).providerCode()).isEqualTo(
                "EXTERNAL_RISK_MOCK_V1"
        );
        assertThat(service(request -> new ExternalRiskProviderResponse(
                "A".repeat(64),
                CUTOFF,
                List.of()
        )).lookup(command()).providerCode()).hasSize(64);

        for (String invalidProviderCode : List.of(
                "SAFE\nforged=value",
                "SAFE\rFORGED",
                "SAFE\tFORGED",
                "SAFE\u0000FORGED",
                " EXTERNAL_RISK_MOCK_V1",
                "EXTERNAL_RISK_MOCK_V1 ",
                "external_risk_mock_v1",
                "SAFE-HYPHEN",
                "A".repeat(65)
        )) {
            assertInvalidResponse(new ExternalRiskProviderResponse(
                    invalidProviderCode,
                    CUTOFF,
                    List.of()
            ));
        }
    }

    @Test
    void acceptsExactlyThreeUniqueSupportedMatches() {
        ExternalRiskSnapshot snapshot = service(
                request -> response(List.of(
                        senderMatch(),
                        recipientMatch(),
                        deviceMatch()
                ))
        ).lookup(command());

        assertThat(snapshot.policyResult()).isEqualTo(
                ExternalRiskPolicyResult.MATCHED
        );
        assertThat(snapshot.matches()).hasSize(3);
    }

    @Test
    void rejectsFutureAndSubMicrosecondProviderTimes() {
        assertInvalidResponse(new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CLOCK_INSTANT.plusSeconds(1).truncatedTo(
                        java.time.temporal.ChronoUnit.MICROS
                ),
                List.of()
        ));
        assertInvalidResponse(new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF.plusNanos(1),
                List.of()
        ));
    }

    @Test
    void preservesTypedFailureCategoryCauseAndDoesNotRetryOrFallback() {
        ExternalRiskLookupPort port = mock(ExternalRiskLookupPort.class);
        IllegalStateException cause = new IllegalStateException("safe cause");
        ExternalRiskLookupException timeout = new ExternalRiskLookupException(
                ExternalRiskFailureCategory.TIMEOUT,
                cause
        );
        when(port.lookup(any())).thenThrow(timeout);

        assertThatThrownBy(() -> service(port).lookup(command()))
                .isSameAs(timeout)
                .hasCause(cause);
        verify(port).lookup(any());
    }

    @Test
    void doesNotHideUnexpectedPortProgrammingErrors() {
        ExternalRiskLookupPort port = mock(ExternalRiskLookupPort.class);
        IllegalArgumentException programmingError = new IllegalArgumentException(
                "programming error"
        );
        when(port.lookup(any())).thenThrow(programmingError);

        assertThatThrownBy(() -> service(port).lookup(command()))
                .isSameAs(programmingError);
        verify(port).lookup(any());
    }

    @Test
    void usesFixedUtcClockOnceAndNormalizesLookedUpAtToMicroseconds() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(CLOCK_INSTANT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        ExternalRiskSnapshot snapshot = new ExternalRiskPolicyService(
                request -> response(List.of(senderMatch())),
                clock
        ).lookup(command());

        assertThat(snapshot.lookedUpAt()).isEqualTo(
                Instant.parse("2026-08-17T03:00:01.654321Z")
        );
        assertThat(snapshot.evaluationCutoffAt()).isEqualTo(CUTOFF);
        verify(clock).instant();
    }

    @Test
    void snapshotAndProviderCollectionsAreImmutable() {
        List<ExternalRiskMatch> source = new ArrayList<>();
        source.add(senderMatch());
        ExternalRiskProviderResponse providerResponse = response(source);
        source.clear();

        ExternalRiskSnapshot snapshot = service(
                request -> providerResponse
        ).lookup(command());

        assertThat(providerResponse.matches()).containsExactly(senderMatch());
        assertThatThrownBy(() -> snapshot.matches().add(deviceMatch()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void logsOnlyApprovedFieldsAndNeverSentinelReferences(
            CapturedOutput output
    ) {
        String sentinel = "SENTINEL_SECRET_REFERENCE";
        ExternalRiskLookupCommand command = new ExternalRiskLookupCommand(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                sentinel,
                sentinel,
                sentinel,
                sentinel,
                TRACE_ID
        );

        service(request -> response(List.of(senderMatch()))).lookup(command);

        assertThat(output).contains(
                TRACE_ID,
                "EXTERNAL_RISK_MOCK_V1",
                "SUCCEEDED",
                "MATCHED",
                "matchCount=1"
        ).doesNotContain(sentinel, TRANSACTION_ID.toString());
    }

    @Test
    void unsafeProviderCodeIsNeverCopiedToFailureLog(CapturedOutput output) {
        String maliciousProviderCode = "SAFE\nforged=value";

        assertInvalidResponse(new ExternalRiskProviderResponse(
                maliciousProviderCode,
                CUTOFF,
                List.of()
        ));

        assertThat(output)
                .contains(TRACE_ID, "INVALID_RESPONSE")
                .doesNotContain(maliciousProviderCode, "forged=value");
    }

    @Test
    void failureLogContainsOnlyTraceAndCategoryNotReferences(
            CapturedOutput output
    ) {
        String sentinel = "SENTINEL_SECRET_REFERENCE";
        ExternalRiskLookupCommand command = new ExternalRiskLookupCommand(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                sentinel,
                sentinel,
                sentinel,
                sentinel,
                TRACE_ID
        );
        ExternalRiskLookupPort port = request -> {
            throw new ExternalRiskLookupException(
                    ExternalRiskFailureCategory.UNAVAILABLE
            );
        };

        assertThatThrownBy(() -> service(port).lookup(command))
                .isInstanceOf(ExternalRiskLookupException.class);
        assertThat(output).contains(TRACE_ID, "UNAVAILABLE")
                .doesNotContain(sentinel, TRANSACTION_ID.toString());
    }

    @Test
    void invalidCommandDoesNotCallPortOrRevealReference() {
        ExternalRiskLookupPort port = mock(ExternalRiskLookupPort.class);
        String sentinel = " SENTINEL_SECRET_REFERENCE ";

        assertThatThrownBy(() -> new ExternalRiskLookupCommand(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                sentinel,
                "sender-ref",
                null,
                null,
                TRACE_ID
        )).isInstanceOf(ExternalRiskLookupException.class)
                .hasMessage("External Risk lookup request is invalid")
                .hasMessageNotContaining(sentinel);
        verify(port, never()).lookup(any());
    }

    private void assertInvalidResponse(ExternalRiskProviderResponse response) {
        ExternalRiskLookupPort port = mock(ExternalRiskLookupPort.class);
        when(port.lookup(any())).thenReturn(response);

        assertThatThrownBy(() -> service(port).lookup(command()))
                .isInstanceOfSatisfying(
                        ExternalRiskLookupException.class,
                        exception -> assertThat(exception.category()).isEqualTo(
                                ExternalRiskFailureCategory.INVALID_RESPONSE
                        )
                );
        verify(port).lookup(any());
    }

    private ExternalRiskPolicyService service(ExternalRiskLookupPort port) {
        return new ExternalRiskPolicyService(port, CLOCK);
    }

    private ExternalRiskLookupCommand command() {
        return new ExternalRiskLookupCommand(
                TRANSACTION_ID,
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                "recipient-ref",
                "device-ref",
                TRACE_ID
        );
    }

    private ExternalRiskProviderResponse response(List<ExternalRiskMatch> matches) {
        return new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                matches
        );
    }

    private ExternalRiskMatch senderMatch() {
        return new ExternalRiskMatch(
                ExternalRiskSubjectType.SENDER_ACCOUNT,
                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
        );
    }

    private ExternalRiskMatch recipientMatch() {
        return new ExternalRiskMatch(
                ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT
        );
    }

    private ExternalRiskMatch deviceMatch() {
        return new ExternalRiskMatch(
                ExternalRiskSubjectType.DEVICE,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        );
    }
}
