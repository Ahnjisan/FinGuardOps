package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.externalrisk.exception.InvalidExternalRiskFailureSnapshotException;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalRiskFailureSnapshotServiceTest {

    private static final long RECORD_ID = 172L;
    private static final Instant FINISHED_AT =
            Instant.parse("2026-08-27T08:17:31.123456Z");

    @Mock
    private IdempotencyService idempotencyService;

    private ExternalRiskFailureSnapshotCodec codec;
    private ExternalRiskFailureSnapshotService service;

    @BeforeEach
    void setUp() {
        codec = new ExternalRiskFailureSnapshotCodec(new ObjectMapper());
        service = new ExternalRiskFailureSnapshotService(
                idempotencyService,
                codec
        );
    }

    @Test
    void persistsEveryTypedCategoryUsingTimestampProvidedByIdempotencyBoundary() {
        for (ExternalRiskFailureCategory category
                : ExternalRiskFailureCategory.values()) {
            String failureCode =
                    ExternalRiskFailureSnapshot.failureCodeFor(category);
            when(idempotencyService.failWithSnapshot(
                    eq(RECORD_ID),
                    eq(failureCode),
                    any()
            )).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Function<Instant, JsonNode> factory = invocation.getArgument(2);
                JsonNode encoded = factory.apply(FINISHED_AT);
                return new IdempotencyClaimResult.FailedWithSnapshot(
                        failureCode,
                        encoded.toString(),
                        FINISHED_AT
                );
            });

            ExternalRiskFailureSnapshot stored = service.persist(
                    RECORD_ID,
                    new ExternalRiskLookupException(category)
            );

            assertThat(stored.failureCategory()).isEqualTo(category);
            assertThat(stored.responseBody().code()).isEqualTo(failureCode);
            assertThat(stored.finalizedAt()).isEqualTo(FINISHED_AT);
        }
    }

    @Test
    void decodesStoredTypedFailureStrictlyAndFailsClosedOnCorruption() {
        ExternalRiskFailureSnapshot snapshot = ExternalRiskFailureSnapshot.from(
                ExternalRiskFailureCategory.UNAVAILABLE,
                FINISHED_AT
        );
        String json = codec.encode(snapshot).toString();

        assertThat(service.decode(new IdempotencyClaimResult.FailedWithSnapshot(
                "DEPENDENCY_UNAVAILABLE",
                json,
                FINISHED_AT
        ))).isEqualTo(snapshot);
        assertThatThrownBy(() -> service.decode(
                new IdempotencyClaimResult.FailedWithSnapshot(
                        "INTERNAL_ERROR",
                        json,
                        FINISHED_AT
                )
        )).isInstanceOf(InvalidExternalRiskFailureSnapshotException.class);
    }
}
