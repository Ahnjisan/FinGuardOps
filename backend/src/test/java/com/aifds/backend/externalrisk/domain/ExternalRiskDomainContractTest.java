package com.aifds.backend.externalrisk.domain;

import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalRiskDomainContractTest {

    private static final UUID TRANSACTION_ID = UUID.fromString(
            "52000000-0000-4000-8000-000000000001"
    );
    private static final Instant CUTOFF = Instant.parse(
            "2026-08-17T05:00:00.123456Z"
    );

    @Test
    void commandAcceptsNullableRecipientAndDeviceReferences() {
        ExternalRiskLookupCommand command = command(
                TRANSACTION_ID,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        );

        assertThat(command.recipientAccountRef()).isNull();
        assertThat(command.deviceRef()).isNull();
    }

    @Test
    void commandRejectsMissingInvalidAndSubMicrosecondInputsSafely() {
        assertInvalid(() -> command(
                null,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        ));
        assertInvalid(() -> command(
                UUID.fromString("52000000-0000-1000-8000-000000000001"),
                CUTOFF,
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        ));
        assertInvalid(() -> command(
                TRANSACTION_ID,
                CUTOFF.plusNanos(1),
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        ));
        assertInvalid(() -> command(
                TRANSACTION_ID,
                CUTOFF,
                "",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        ));
        assertInvalid(() -> command(
                TRANSACTION_ID,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                null,
                null,
                "short"
        ));
        assertInvalid(() -> new ExternalRiskLookupCommand(
                TRANSACTION_ID,
                null,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-ext-risk-0001"
        ));
        assertInvalid(() -> command(
                TRANSACTION_ID,
                CUTOFF,
                "customer-ref",
                "",
                null,
                null,
                "trace-ext-risk-0001"
        ));
        assertInvalid(() -> command(
                TRANSACTION_ID,
                CUTOFF,
                "customer-ref",
                "sender-ref",
                " recipient-ref ",
                null,
                "trace-ext-risk-0001"
        ));
    }

    @Test
    void typedExceptionUsesFixedSafeMessageAndPreservesCause() {
        String sentinel = "SENTINEL_SECRET_REFERENCE";
        IllegalStateException cause = new IllegalStateException(sentinel);
        ExternalRiskLookupException exception = new ExternalRiskLookupException(
                ExternalRiskFailureCategory.UNAVAILABLE,
                cause
        );

        assertThat(exception.category()).isEqualTo(
                ExternalRiskFailureCategory.UNAVAILABLE
        );
        assertThat(exception).hasCause(cause);
        assertThat(exception.getMessage())
                .isEqualTo("External Risk provider is unavailable")
                .doesNotContain(sentinel);
    }

    @Test
    void failureSnapshotMapsEveryCategoryToApprovedPublicResponse() {
        for (ExternalRiskFailureCategory category
                : ExternalRiskFailureCategory.values()) {
            ExternalRiskFailureSnapshot snapshot =
                    ExternalRiskFailureSnapshot.from(category, CUTOFF);

            assertThat(snapshot.finalizedAt()).isEqualTo(CUTOFF);
            assertThat(snapshot.responseBody().fieldErrors()).isEmpty();
            switch (category) {
                case TIMEOUT -> assertFailureMapping(
                        snapshot,
                        503,
                        "DEPENDENCY_TIMEOUT",
                        "탐지 서비스를 사용할 수 없습니다."
                );
                case UNAVAILABLE -> assertFailureMapping(
                        snapshot,
                        503,
                        "DEPENDENCY_UNAVAILABLE",
                        "탐지 서비스를 사용할 수 없습니다."
                );
                case INVALID_REQUEST,
                     UNSUPPORTED_CAPABILITY,
                     INVALID_RESPONSE,
                     TRANSFORMATION_ERROR -> assertFailureMapping(
                        snapshot,
                        500,
                        "INTERNAL_ERROR",
                        "요청을 처리하는 중 오류가 발생했습니다."
                );
            }
        }
    }

    @Test
    void failureSnapshotRejectsMappingChangesAndCopiesFieldErrors() {
        java.util.ArrayList<String> fieldErrors = new java.util.ArrayList<>();
        ExternalRiskFailureSnapshot.ResponseBody body =
                new ExternalRiskFailureSnapshot.ResponseBody(
                        "DEPENDENCY_TIMEOUT",
                        "탐지 서비스를 사용할 수 없습니다.",
                        fieldErrors
                );
        fieldErrors.add("unsafe");

        assertThat(body.fieldErrors()).isEmpty();
        assertThatThrownBy(() -> body.fieldErrors().add("unsafe"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ExternalRiskFailureSnapshot(
                body,
                500,
                ExternalRiskFailureCategory.TIMEOUT,
                CUTOFF
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("DEPENDENCY_TIMEOUT");
    }

    @Test
    void snapshotExposesNeitherTraceNorReferencesAndCopiesMatches() {
        List<ExternalRiskMatch> matches = List.of(new ExternalRiskMatch(
                ExternalRiskSubjectType.DEVICE,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        ));
        ExternalRiskSnapshot snapshot = new ExternalRiskSnapshot(
                TRANSACTION_ID,
                CUTOFF,
                CUTOFF.plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.MATCHED,
                matches
        );

        assertThat(ExternalRiskSnapshot.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain(
                        "traceId",
                        "externalCustomerRef",
                        "senderAccountRef",
                        "recipientAccountRef",
                        "deviceRef"
                );
        assertThatThrownBy(() -> snapshot.matches().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commandAndProviderRequestToStringRedactEverySensitiveField() {
        String sentinelCustomer = "SENTINEL_CUSTOMER";
        String sentinelSender = "SENTINEL_SENDER";
        String sentinelRecipient = "SENTINEL_RECIPIENT";
        String sentinelDevice = "SENTINEL_DEVICE";
        String sentinelTrace = "SENTINEL_TRACE_0001";
        ExternalRiskLookupCommand command = command(
                TRANSACTION_ID,
                CUTOFF,
                sentinelCustomer,
                sentinelSender,
                sentinelRecipient,
                sentinelDevice,
                sentinelTrace
        );
        ExternalRiskProviderRequest request = new ExternalRiskProviderRequest(
                TransactionType.ACCOUNT_TRANSFER,
                CUTOFF,
                sentinelCustomer,
                sentinelSender,
                sentinelRecipient,
                sentinelDevice,
                sentinelTrace
        );

        assertRedacted(command.toString(), "ExternalRiskLookupCommand");
        assertRedacted(request.toString(), "ExternalRiskProviderRequest");

        ExternalRiskLookupCommand nullableCommand = command(
                TRANSACTION_ID,
                CUTOFF,
                sentinelCustomer,
                sentinelSender,
                null,
                null,
                sentinelTrace
        );
        String nullableText = nullableCommand.toString();
        assertThat(nullableText)
                .contains(
                        "recipientReferencePresent=false",
                        "deviceReferencePresent=false"
                )
                .doesNotContain("null");
    }

    @Test
    void providerResponseRejectsInvalidMatchStructureBeforeCopying() {
        assertInvalidResponse(() -> new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                null
        ));
        assertInvalidResponse(() -> new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                Arrays.asList(senderMatch(), null)
        ));
        assertInvalidResponse(() -> new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                List.of(
                        senderMatch(),
                        recipientMatch(),
                        deviceMatch(),
                        senderMatch()
                )
        ));
        assertInvalidResponse(() -> new ExternalRiskProviderResponse(
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                oversizedListThatMustNotBeRead()
        ));

        ExternalRiskProviderResponse exactMaximum =
                new ExternalRiskProviderResponse(
                        "EXTERNAL_RISK_MOCK_V1",
                        CUTOFF,
                        List.of(senderMatch(), recipientMatch(), deviceMatch())
                );
        assertThat(exactMaximum.matches()).hasSize(3);
    }

    @Test
    void snapshotConstructorRejectsInvalidMatchAndPolicyInvariants() {
        assertInvalidSnapshot(List.of(new ExternalRiskMatch(
                ExternalRiskSubjectType.SENDER_ACCOUNT,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        )), ExternalRiskPolicyResult.MATCHED);
        assertInvalidSnapshot(
                List.of(senderMatch(), senderMatch()),
                ExternalRiskPolicyResult.MATCHED
        );
        assertInvalidSnapshot(List.of(), ExternalRiskPolicyResult.MATCHED);
        assertInvalidSnapshot(
                List.of(senderMatch()),
                ExternalRiskPolicyResult.UNMATCHED
        );
        assertThatThrownBy(() -> new ExternalRiskSnapshot(
                TRANSACTION_ID,
                CUTOFF,
                CUTOFF.plusSeconds(1),
                "unsafe-provider",
                CUTOFF,
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalRiskSnapshot(
                TRANSACTION_ID,
                CUTOFF,
                CUTOFF.plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF.plusSeconds(2),
                ExternalRiskLookupStatus.SUCCEEDED,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalRiskSnapshot(
                TRANSACTION_ID,
                CUTOFF,
                CUTOFF.plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                null,
                ExternalRiskPolicyResult.UNMATCHED,
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRedacted(String value, String recordName) {
        assertThat(value)
                .contains(recordName, "sensitiveFields=REDACTED")
                .doesNotContain(
                        TRANSACTION_ID.toString(),
                        "SENTINEL_CUSTOMER",
                        "SENTINEL_SENDER",
                        "SENTINEL_RECIPIENT",
                        "SENTINEL_DEVICE",
                        "SENTINEL_TRACE_0001"
                );
    }

    private void assertFailureMapping(
            ExternalRiskFailureSnapshot snapshot,
            int httpStatus,
            String code,
            String message
    ) {
        assertThat(snapshot.httpStatus()).isEqualTo(httpStatus);
        assertThat(snapshot.responseBody().code()).isEqualTo(code);
        assertThat(snapshot.responseBody().message()).isEqualTo(message);
    }

    private void assertInvalidResponse(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        assertThatThrownBy(call).isInstanceOfSatisfying(
                ExternalRiskLookupException.class,
                exception -> assertThat(exception.category()).isEqualTo(
                        ExternalRiskFailureCategory.INVALID_RESPONSE
                )
        );
    }

    private void assertInvalidSnapshot(
            List<ExternalRiskMatch> matches,
            ExternalRiskPolicyResult policyResult
    ) {
        assertThatThrownBy(() -> new ExternalRiskSnapshot(
                TRANSACTION_ID,
                CUTOFF,
                CUTOFF.plusSeconds(1),
                "EXTERNAL_RISK_MOCK_V1",
                CUTOFF,
                ExternalRiskLookupStatus.SUCCEEDED,
                policyResult,
                matches
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SENTINEL");
    }

    private List<ExternalRiskMatch> oversizedListThatMustNotBeRead() {
        return new AbstractList<>() {
            @Override
            public ExternalRiskMatch get(int index) {
                throw new AssertionError("oversized list must not be read");
            }

            @Override
            public Iterator<ExternalRiskMatch> iterator() {
                throw new AssertionError("oversized list must not be iterated");
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };
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

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ExternalRiskLookupException.class,
                        exception -> {
                            assertThat(exception.category()).isEqualTo(
                                    ExternalRiskFailureCategory.INVALID_REQUEST
                            );
                            assertThat(exception.getMessage()).isEqualTo(
                                    "External Risk lookup request is invalid"
                            );
                        }
                );
    }

    private ExternalRiskLookupCommand command(
            UUID transactionId,
            Instant cutoff,
            String customerRef,
            String senderRef,
            String recipientRef,
            String deviceRef,
            String traceId
    ) {
        return new ExternalRiskLookupCommand(
                transactionId,
                TransactionType.ACCOUNT_TRANSFER,
                cutoff,
                customerRef,
                senderRef,
                recipientRef,
                deviceRef,
                traceId
        );
    }
}
