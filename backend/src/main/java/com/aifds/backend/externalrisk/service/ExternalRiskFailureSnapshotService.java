package com.aifds.backend.externalrisk.service;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskLookupException;
import com.aifds.backend.idempotency.service.IdempotencyClaimResult;
import com.aifds.backend.idempotency.service.IdempotencyService;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ExternalRiskFailureSnapshotService {

    private final IdempotencyService idempotencyService;
    private final ExternalRiskFailureSnapshotCodec snapshotCodec;

    public ExternalRiskFailureSnapshotService(
            IdempotencyService idempotencyService,
            ExternalRiskFailureSnapshotCodec snapshotCodec
    ) {
        this.idempotencyService = idempotencyService;
        this.snapshotCodec = snapshotCodec;
    }

    public ExternalRiskFailureSnapshot persist(
            long idempotencyRecordId,
            ExternalRiskLookupException failure
    ) {
        Objects.requireNonNull(failure, "failure must not be null");

        IdempotencyClaimResult.FailedWithSnapshot stored =
                idempotencyService.failWithSnapshot(
                        idempotencyRecordId,
                        ExternalRiskFailureSnapshot.failureCodeFor(
                                failure.category()
                        ),
                        finalizedAt -> snapshotCodec.encode(
                                ExternalRiskFailureSnapshot.from(
                                        failure.category(),
                                        finalizedAt
                                )
                        )
                );
        return decode(stored);
    }

    public ExternalRiskFailureSnapshot decode(
            IdempotencyClaimResult.FailedWithSnapshot stored
    ) {
        Objects.requireNonNull(stored, "stored must not be null");
        return snapshotCodec.decode(
                stored.responseSnapshotJson(),
                stored.failureCode(),
                stored.finishedAt()
        );
    }
}
