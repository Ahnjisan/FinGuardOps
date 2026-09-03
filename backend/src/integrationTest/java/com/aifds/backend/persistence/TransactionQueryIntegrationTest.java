package com.aifds.backend.persistence;

import com.aifds.backend.common.trace.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(
        authorities = "transaction:read"
)
class TransactionQueryIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String PATH = "/api/v1/transactions";
    private static final UUID ACCOUNT_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000001"
    );
    private static final UUID ATM_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000002"
    );
    private static final UUID OPEN_BANKING_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000003"
    );
    private static final UUID LOAN_ID = UUID.fromString(
            "10000000-0000-4000-9000-000000000004"
    );
    private static final Instant TIME_ZERO =
            Instant.parse("2026-07-23T00:00:00Z");
    private static final Instant TIME_ONE =
            Instant.parse("2026-07-23T01:00:00Z");
    private static final Instant TIME_TWO =
            Instant.parse("2026-07-23T02:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTransactions() {
        insert(
                ACCOUNT_ID,
                "ACCOUNT_TRANSFER",
                "1250000",
                TIME_ZERO,
                "CustomerExact",
                "acct_account_sender",
                "acct_account_recipient",
                "MOBILE_BANKING",
                "device_account",
                "RECEIVED"
        );
        insert(
                ATM_ID,
                "ATM_WITHDRAWAL",
                "2000",
                TIME_ONE,
                "CustomerAtm",
                "acct_atm_sender",
                null,
                "ATM",
                null,
                "HELD"
        );
        insert(
                OPEN_BANKING_ID,
                "OPEN_BANKING_TRANSFER",
                "3000",
                TIME_ONE,
                "CustomerOpen",
                "acct_open_sender",
                "acct_target",
                "OPEN_BANKING",
                null,
                "ANALYZED"
        );
        insert(
                LOAN_ID,
                "LOAN_DISBURSED",
                "4000",
                TIME_TWO,
                "CustomerLoan",
                "acct_target",
                null,
                "CORE_BANKING",
                "device_loan",
                "APPROVED"
        );
    }

    @Test
    void listsWithoutFiltersUsingApprovedDefaultsAndStableDescendingOrder()
            throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(LOAN_ID.toString()))
                .andExpect(jsonPath("$.content[1].transactionId")
                        .value(OPEN_BANKING_ID.toString()))
                .andExpect(jsonPath("$.content[2].transactionId")
                        .value(ATM_ID.toString()))
                .andExpect(jsonPath("$.content[3].transactionId")
                        .value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void appliesEachPersistedFilterAndExactCaseSensitiveReferences()
            throws Exception {
        expectOnly(
                "transactionType",
                "ATM_WITHDRAWAL",
                ATM_ID
        );
        expectOnly("processingStatus", "ANALYZED", OPEN_BANKING_ID);
        expectOnly(
                "externalCustomerRef",
                "CustomerExact",
                ACCOUNT_ID
        );

        mockMvc.perform(get(PATH)
                        .queryParam(
                                "externalCustomerRef",
                                "customerexact"
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void accountRefMatchesSenderOrRecipientWithExactCase()
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("accountRef", "acct_target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(LOAN_ID.toString()))
                .andExpect(jsonPath("$.content[1].transactionId")
                        .value(OPEN_BANKING_ID.toString()));

        mockMvc.perform(get(PATH)
                        .queryParam("accountRef", "ACCT_TARGET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void usesInclusiveFromAndExclusiveToAndSupportsCombinedFilters()
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtFrom",
                                TIME_ONE.toString()
                        )
                        .queryParam(
                                "occurredAtTo",
                                TIME_TWO.toString()
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(OPEN_BANKING_ID.toString()))
                .andExpect(jsonPath("$.content[1].transactionId")
                        .value(ATM_ID.toString()));

        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtFrom",
                                TIME_ONE.toString()
                        )
                        .queryParam(
                                "occurredAtTo",
                                TIME_TWO.toString()
                        )
                        .queryParam(
                                "transactionType",
                                "OPEN_BANKING_TRANSFER"
                        )
                        .queryParam(
                                "processingStatus",
                                "ANALYZED"
                        )
                        .queryParam(
                                "externalCustomerRef",
                                "CustomerOpen"
                        )
                        .queryParam("accountRef", "acct_target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(OPEN_BANKING_ID.toString()));
    }

    @Test
    void paginatesWithTotalCountAndStableAscendingAndDescendingSort()
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("page", "0")
                        .queryParam("size", "2")
                        .queryParam("sort", "occurredAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(LOAN_ID.toString()))
                .andExpect(jsonPath("$.content[1].transactionId")
                        .value(OPEN_BANKING_ID.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(false));

        mockMvc.perform(get(PATH)
                        .queryParam("page", "1")
                        .queryParam("size", "2")
                        .queryParam("sort", "occurredAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(ATM_ID.toString()))
                .andExpect(jsonPath("$.content[1].transactionId")
                        .value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.page.first").value(false))
                .andExpect(jsonPath("$.page.last").value(true));

        mockMvc.perform(get(PATH)
                        .queryParam("sort", "occurredAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.content[1].transactionId")
                        .value(ATM_ID.toString()))
                .andExpect(jsonPath("$.content[2].transactionId")
                        .value(OPEN_BANKING_ID.toString()))
                .andExpect(jsonPath("$.content[3].transactionId")
                        .value(LOAN_ID.toString()));
    }

    @Test
    void returnsEmptyListForEmptyRangeAndUnmatchedFilter()
            throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam(
                                "occurredAtFrom",
                                TIME_ONE.toString()
                        )
                        .queryParam(
                                "occurredAtTo",
                                TIME_ONE.toString()
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true));
    }

    @Test
    void returnsDetailNullableFieldsAmountAndCurrentTraceId()
            throws Exception {
        String traceId = "trace_query_integration_01";

        mockMvc.perform(get(PATH + "/" + ATM_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.transaction.transactionId")
                        .value(ATM_ID.toString()))
                .andExpect(jsonPath("$.transaction.amount").value("2000"))
                .andExpect(jsonPath("$.transaction.recipientAccountRef")
                        .value(nullValue()))
                .andExpect(jsonPath("$.transaction.deviceRef")
                        .value(nullValue()));
    }

    @Test
    void returnsSafeNotFoundWithSameHeaderAndBodyTraceId()
            throws Exception {
        String traceId = "trace_query_not_found_01";
        UUID missing = UUID.fromString(
                "10000000-0000-4000-9000-000000000099"
        );

        mockMvc.perform(get(PATH + "/" + missing)
                        .header(TraceIdFilter.TRACE_ID_HEADER, traceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("요청한 거래를 찾을 수 없습니다."))
                .andExpect(header().string(
                        TraceIdFilter.TRACE_ID_HEADER,
                        traceId
                ))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    private void expectOnly(
            String parameter,
            String value,
            UUID expectedId
    ) throws Exception {
        mockMvc.perform(get(PATH).queryParam(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value(expectedId.toString()));
    }

    private void insert(
            UUID transactionId,
            String transactionType,
            String amount,
            Instant occurredAt,
            String externalCustomerRef,
            String senderAccountRef,
            String recipientAccountRef,
            String channel,
            String deviceRef,
            String processingStatus
    ) {
        Instant createdAt = occurredAt.plusSeconds(1);
        jdbcTemplate.update("""
                        INSERT INTO financial_transaction (
                            transaction_id,
                            transaction_type,
                            amount,
                            currency_code,
                            occurred_at,
                            external_customer_ref,
                            sender_account_ref,
                            recipient_account_ref,
                            channel,
                            device_ref,
                            processing_status,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, 'KRW', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transactionId,
                transactionType,
                new java.math.BigDecimal(amount),
                Timestamp.from(occurredAt),
                externalCustomerRef,
                senderAccountRef,
                recipientAccountRef,
                channel,
                deviceRef,
                processingStatus,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }
}
