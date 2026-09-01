package com.aifds.backend.fraudcase.service;

import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FraudCaseWorkflowMapperTest {

    private final FraudCaseWorkflowMapper mapper =
            new FraudCaseWorkflowMapper();

    @Test
    void mapsOnlyApprovedMutationFieldsAndIncreasedVersion() {
        UUID caseId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-01T00:00:00Z");
        FraudCase fraudCase = FraudCase.open(caseId, createdAt);
        fraudCase.startReview(
                "10000000-0000-4000-9000-000000000001",
                createdAt.plusSeconds(1)
        );
        ReflectionTestUtils.setField(fraudCase, "id", 99L);
        ReflectionTestUtils.setField(fraudCase, "concurrencyVersion", 1L);

        FraudCaseMutationResponse response = mapper.toResponse(
                fraudCase,
                "trace_case_mapper_01"
        );

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.concurrencyVersion()).isEqualTo(1L);
        assertThat(response.finalDisposition()).isNull();
        assertThat(response.closedAt()).isNull();
        assertThat(response.traceId()).isEqualTo("trace_case_mapper_01");
        assertThat(FraudCaseMutationResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "caseId",
                        "caseStatus",
                        "finalDisposition",
                        "assigneeRef",
                        "reviewStartedAt",
                        "closedAt",
                        "lastChangedAt",
                        "concurrencyVersion",
                        "traceId"
                )
                .doesNotContain("id", "fraudCaseId");
    }

    @Test
    void rejectsNullEntity() {
        assertThatNullPointerException().isThrownBy(() ->
                mapper.toResponse(null, "trace_case_mapper_01")
        ).withMessage("fraudCase must not be null");
    }
}
