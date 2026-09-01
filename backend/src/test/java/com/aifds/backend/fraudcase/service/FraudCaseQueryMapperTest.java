package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCaseQueryMapperTest {

    private final FraudCaseQueryMapper mapper = new FraudCaseQueryMapper();

    @Test
    void mapsOnlyApprovedListAndDetailValuesIncludingNulls() {
        FraudCase fraudCase = mock(FraudCase.class);
        UUID caseId = UUID.fromString(
                "10000000-0000-4000-9000-000000000001"
        );
        Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant changedAt = Instant.parse("2026-08-02T00:00:00Z");
        when(fraudCase.getCaseId()).thenReturn(caseId);
        when(fraudCase.getCaseStatus()).thenReturn(FraudCaseStatus.OPEN);
        when(fraudCase.getCreatedAt()).thenReturn(createdAt);
        when(fraudCase.getLastChangedAt()).thenReturn(changedAt);
        when(fraudCase.getConcurrencyVersion()).thenReturn(3L);

        var list = mapper.toListItem(fraudCase, 2L);
        var detail = mapper.toDetailItem(fraudCase, 2L);

        assertThat(list.caseId()).isEqualTo(caseId);
        assertThat(list.finalDisposition()).isNull();
        assertThat(list.assigneeRef()).isNull();
        assertThat(list.relatedTransactionCount()).isEqualTo(2L);
        assertThat(detail.reviewStartedAt()).isNull();
        assertThat(detail.closedAt()).isNull();
        assertThat(detail.concurrencyVersion()).isEqualTo(3L);
    }

    @Test
    void rejectsNullEntityAndNegativeCount() {
        assertThatThrownBy(() -> mapper.toListItem(null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.toDetailItem(mock(FraudCase.class), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
