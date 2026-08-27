package com.aifds.backend.externalrisk.client;

import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpMatchResponse;
import com.aifds.backend.externalrisk.client.dto.ExternalRiskHttpResponse;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ExternalRiskHttpMapperTest {

    private final ExternalRiskHttpMapper mapper = new ExternalRiskHttpMapper();

    @Test
    void mapsTheExactSevenRequestFieldsIncludingOptionalNulls() {
        var mapped = mapper.toHttpRequest(request());

        assertThat(mapped.transactionType())
                .isEqualTo(TransactionType.ACCOUNT_TRANSFER);
        assertThat(mapped.evaluationCutoffAt())
                .isEqualTo(Instant.parse("2026-08-27T01:02:03.123456Z"));
        assertThat(mapped.externalCustomerRef()).isEqualTo("customer-ref");
        assertThat(mapped.senderAccountRef()).isEqualTo("sender-ref");
        assertThat(mapped.recipientAccountRef()).isNull();
        assertThat(mapped.deviceRef()).isNull();
        assertThat(mapped.traceId()).isEqualTo("trace-external-risk-0001");
    }

    @Test
    void mapsAValidatedStrictResponseToTheDomain() {
        var response = new ExternalRiskHttpResponse(
                "PROVIDER_V1",
                Instant.parse("2026-08-27T01:02:03.123456Z"),
                List.of(new ExternalRiskHttpMatchResponse(
                        ExternalRiskSubjectType.SENDER_ACCOUNT,
                        ExternalRiskType.SUSPICIOUS_ACCOUNT,
                        ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT
                ))
        );

        var mapped = mapper.toDomainResponse(response);

        assertThat(mapped.providerCode()).isEqualTo("PROVIDER_V1");
        assertThat(mapped.providerAsOf()).isEqualTo(response.providerAsOf());
        assertThat(mapped.matches()).hasSize(1);
    }

    @Test
    void rejectsNullUnsupportedDuplicateAndOverPrecisionResponses() {
        assertCategory(null, ExternalRiskFailureCategory.INVALID_RESPONSE);
        assertCategory(new ExternalRiskHttpResponse(
                "PROVIDER_V1",
                Instant.parse("2026-08-27T01:02:03.123456789Z"),
                List.of()
        ), ExternalRiskFailureCategory.INVALID_RESPONSE);
        ExternalRiskHttpMatchResponse unsupported = new ExternalRiskHttpMatchResponse(
                ExternalRiskSubjectType.DEVICE,
                ExternalRiskType.SUSPICIOUS_ACCOUNT,
                ExternalRiskReasonCode.RISK_DEVICE
        );
        assertCategory(new ExternalRiskHttpResponse(
                "PROVIDER_V1",
                Instant.EPOCH,
                List.of(unsupported)
        ), ExternalRiskFailureCategory.INVALID_RESPONSE);
        ExternalRiskHttpMatchResponse valid = new ExternalRiskHttpMatchResponse(
                ExternalRiskSubjectType.DEVICE,
                ExternalRiskType.RISK_DEVICE,
                ExternalRiskReasonCode.RISK_DEVICE
        );
        assertCategory(new ExternalRiskHttpResponse(
                "PROVIDER_V1",
                Instant.EPOCH,
                List.of(valid, valid)
        ), ExternalRiskFailureCategory.INVALID_RESPONSE);
    }

    @Test
    void rejectsNullRequestBeforeAnyHttpMapping() {
        ExternalRiskLookupException failure = catchThrowableOfType(
                ExternalRiskLookupException.class,
                () -> mapper.toHttpRequest(null)
        );
        assertThat(failure.category())
                .isEqualTo(ExternalRiskFailureCategory.INVALID_REQUEST);
    }

    @Test
    void mapsUnexpectedValidatedDomainTransformationFailure() {
        ExternalRiskHttpMapper failingMapper = new ExternalRiskHttpMapper(
                (providerCode, providerAsOf, matches) -> {
                    throw new IllegalArgumentException("raw transformation value");
                }
        );
        ExternalRiskHttpResponse response = new ExternalRiskHttpResponse(
                "PROVIDER_V1",
                Instant.EPOCH,
                List.of()
        );

        ExternalRiskLookupException failure = catchThrowableOfType(
                ExternalRiskLookupException.class,
                () -> failingMapper.toDomainResponse(response)
        );

        assertThat(failure.category())
                .isEqualTo(ExternalRiskFailureCategory.TRANSFORMATION_ERROR);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).doesNotContain("raw transformation value");
    }

    @Test
    void requestToStringRedactsReferences() {
        String value = mapper.toHttpRequest(request()).toString();

        assertThat(value).doesNotContain("customer-ref", "sender-ref");
        assertThat(value).contains("sensitiveFields=REDACTED");
    }

    private void assertCategory(
            ExternalRiskHttpResponse response,
            ExternalRiskFailureCategory category
    ) {
        ExternalRiskLookupException failure = catchThrowableOfType(
                ExternalRiskLookupException.class,
                () -> mapper.toDomainResponse(response)
        );
        assertThat(failure.category()).isEqualTo(category);
    }

    private ExternalRiskProviderRequest request() {
        return new ExternalRiskProviderRequest(
                TransactionType.ACCOUNT_TRANSFER,
                Instant.parse("2026-08-27T01:02:03.123456Z"),
                "customer-ref",
                "sender-ref",
                null,
                null,
                "trace-external-risk-0001"
        );
    }
}
