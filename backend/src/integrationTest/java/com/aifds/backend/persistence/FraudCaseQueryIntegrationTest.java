package com.aifds.backend.persistence;

import com.aifds.backend.common.trace.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser
class FraudCaseQueryIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String PATH = "/api/v1/cases";
    private static final UUID CASE_A = UUID.fromString(
            "20000000-0000-4000-9000-000000000001"
    );
    private static final UUID CASE_B = UUID.fromString(
            "20000000-0000-4000-9000-000000000002"
    );
    private static final UUID CASE_C = UUID.fromString(
            "20000000-0000-4000-9000-000000000003"
    );
    private static final UUID CASE_D = UUID.fromString(
            "20000000-0000-4000-9000-000000000004"
    );
    private static final UUID TRANSACTION_A = UUID.fromString(
            "30000000-0000-4000-9000-000000000001"
    );
    private static final UUID TRANSACTION_B = UUID.fromString(
            "30000000-0000-4000-9000-000000000002"
    );
    private static final UUID TRANSACTION_C = UUID.fromString(
            "30000000-0000-4000-9000-000000000003"
    );
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T02:00:00Z");
    private static final Instant T3 = Instant.parse("2026-08-01T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCasesAndTransactions() {
        insertTransaction(TRANSACTION_A, T0);
        insertTransaction(TRANSACTION_B, T1);
        insertTransaction(TRANSACTION_C, T2);

        insertCase(CASE_A, "OPEN", null, null, T0, T3, null, null);
        insertCase(CASE_B, "OPEN", null, null, T1, T3, null, null);
        insertCase(
                CASE_C,
                "IN_REVIEW",
                null,
                "analyst_ref_01",
                T2,
                T2,
                T2,
                null
        );
        insertCase(
                CASE_D,
                "CLOSED",
                "CONFIRMED_FRAUD",
                "analyst_ref_02",
                T0,
                T1,
                T0,
                T1
        );

        link(1L, 1L, T0);
        link(1L, 2L, T1);
        link(3L, 3L, T2);
    }

    @Test
    void appliesV10AndCreatesApprovedIndex() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '10'",
                Boolean.class
        );
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'fraud_case'
                  AND indexname = 'ix_fraud_case_last_changed'
                  AND indexdef LIKE '%(last_changed_at, id)%'
                """, Integer.class);

        assertThat(applied).isTrue();
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void paginatesDeterministicallyWhenLastChangedAtTies()
            throws Exception {
        expectCase(0, 1, "lastChangedAt,desc", CASE_B, 4, 4);
        expectCase(1, 1, "lastChangedAt,desc", CASE_A, 4, 4);
        expectCase(2, 1, "lastChangedAt,desc", CASE_C, 4, 4);
        expectCase(0, 1, "lastChangedAt,asc", CASE_D, 4, 4);
        expectCase(2, 1, "lastChangedAt,asc", CASE_A, 4, 4);
        expectCase(3, 1, "lastChangedAt,asc", CASE_B, 4, 4);
    }

    @Test
    void appliesEveryPersistedFilterAndCombinedFilters() throws Exception {
        expectIds("caseStatus", "IN_REVIEW", CASE_C);
        expectIds("finalDisposition", "CONFIRMED_FRAUD", CASE_D);
        expectIds("assigneeRef", "analyst_ref_01", CASE_C);
        expectIds("transactionId", TRANSACTION_A.toString(), CASE_A);

        mockMvc.perform(get(PATH)
                        .queryParam("createdAtFrom", T1.toString())
                        .queryParam("createdAtTo", T2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(CASE_B.toString()));

        mockMvc.perform(get(PATH)
                        .queryParam("lastChangedAtFrom", T2.toString())
                        .queryParam("lastChangedAtTo", T3.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(CASE_C.toString()));

        mockMvc.perform(get(PATH)
                        .queryParam("caseStatus", "OPEN")
                        .queryParam("createdAtFrom", T0.toString())
                        .queryParam("createdAtTo", T1.toString())
                        .queryParam("lastChangedAtFrom", T3.toString())
                        .queryParam("transactionId", TRANSACTION_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(CASE_A.toString()));
    }

    @Test
    void usesInclusiveFromExclusiveToAndEqualRangeIsEmpty()
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("createdAtFrom", T0.toString())
                        .queryParam("createdAtTo", T1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(CASE_A.toString()))
                .andExpect(jsonPath("$.content[1].caseId")
                        .value(CASE_D.toString()));

        mockMvc.perform(get(PATH)
                        .queryParam("createdAtFrom", T1.toString())
                        .queryParam("createdAtTo", T1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void returnsBatchDerivedCountsAndExactDetailContract()
            throws Exception {
        mockMvc.perform(get(PATH).queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(CASE_B.toString()))
                .andExpect(jsonPath("$.content[0].relatedTransactionCount")
                        .value(0))
                .andExpect(jsonPath("$.content[1].caseId")
                        .value(CASE_A.toString()))
                .andExpect(jsonPath("$.content[1].relatedTransactionCount")
                        .value(2))
                .andExpect(jsonPath("$.content[2].caseId")
                        .value(CASE_C.toString()))
                .andExpect(jsonPath("$.content[2].relatedTransactionCount")
                        .value(1));

        String traceId = "trace_case_integration_01";
        mockMvc.perform(get(PATH + "/" + CASE_C)
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.case.caseId").value(CASE_C.toString()))
                .andExpect(jsonPath("$.case.caseStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.case.finalDisposition")
                        .value(nullValue()))
                .andExpect(jsonPath("$.case.assigneeRef")
                        .value("analyst_ref_01"))
                .andExpect(jsonPath("$.case.relatedTransactionCount").value(1))
                .andExpect(jsonPath("$.case.reviewStartedAt")
                        .value(T2.toString()))
                .andExpect(jsonPath("$.case.closedAt").value(nullValue()))
                .andExpect(jsonPath("$.case.concurrencyVersion").value(0));
    }

    @Test
    void returnsEmptyPageAndSafeMissingDetail() throws Exception {
        mockMvc.perform(get(PATH).queryParam("assigneeRef", "missing_ref"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true));

        String missing = "20000000-0000-4000-9000-000000000099";
        mockMvc.perform(get(PATH + "/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("요청한 사건을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    private void expectCase(
            int page,
            int size,
            String sort,
            UUID expected,
            long totalElements,
            int totalPages
    ) throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("page", Integer.toString(page))
                        .queryParam("size", Integer.toString(size))
                        .queryParam("sort", sort))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(expected.toString()))
                .andExpect(jsonPath("$.page.totalElements")
                        .value(totalElements))
                .andExpect(jsonPath("$.page.totalPages").value(totalPages));
    }

    private void expectIds(String parameter, String value, UUID expected)
            throws Exception {
        mockMvc.perform(get(PATH).queryParam(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].caseId")
                        .value(expected.toString()));
    }

    private void insertTransaction(UUID transactionId, Instant occurredAt) {
        Instant storedAt = occurredAt.plusSeconds(1);
        jdbcTemplate.update("""
                        INSERT INTO financial_transaction (
                            transaction_id, transaction_type, amount,
                            currency_code, occurred_at, external_customer_ref,
                            sender_account_ref, recipient_account_ref, channel,
                            device_ref, processing_status, created_at, updated_at
                        ) VALUES (?, 'ACCOUNT_TRANSFER', ?, 'KRW', ?, ?, ?, ?,
                                  'MOBILE_BANKING', ?, 'HELD', ?, ?)
                        """,
                transactionId,
                new BigDecimal("10000"),
                Timestamp.from(occurredAt),
                "customer_ref_" + transactionId,
                "sender_ref_" + transactionId,
                "recipient_ref_" + transactionId,
                "device_ref_" + transactionId,
                Timestamp.from(storedAt),
                Timestamp.from(storedAt)
        );
    }

    private void insertCase(
            UUID caseId,
            String status,
            String disposition,
            String assignee,
            Instant createdAt,
            Instant changedAt,
            Instant reviewStartedAt,
            Instant closedAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO fraud_case (
                            case_id, case_status, final_disposition,
                            assignee_ref, review_started_at, closed_at,
                            concurrency_version, created_at, last_changed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                        """,
                caseId,
                status,
                disposition,
                assignee,
                timestamp(reviewStartedAt),
                timestamp(closedAt),
                Timestamp.from(createdAt),
                Timestamp.from(changedAt)
        );
    }

    private void link(long casePk, long transactionPk, Instant linkedAt) {
        jdbcTemplate.update("""
                        INSERT INTO case_transaction (
                            fraud_case_id, financial_transaction_id, linked_at
                        ) VALUES (?, ?, ?)
                        """,
                casePk,
                transactionPk,
                Timestamp.from(linkedAt)
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
