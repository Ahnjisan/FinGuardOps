package com.aifds.backend.persistence;

import com.aifds.backend.dashboard.FdsDashboardRange;
import com.aifds.backend.dashboard.FdsDashboardRepository;
import com.aifds.backend.dashboard.FdsDashboardService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FdsDashboardIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired FdsDashboardRepository repository;
    @Autowired FdsDashboardService service;
    @Autowired MockMvc mvc;

    private static final String START = "2026-09-10";
    private static final String END = "2026-09-13";

    @Test
    @WithMockUser(authorities = {"transaction:read", "detection:read", "case:read"})
    void aggregatesKstBoundariesVersionsAndManyToManyWithoutDuplication() throws Exception {
        long beforeMidnight = transaction("2026-09-10T14:59:59Z");
        long atMidnight = transaction("2026-09-10T15:00:00Z");
        long late = transaction("2026-09-11T15:00:00Z");
        transaction("2026-09-12T15:00:00Z"); // exclusive end
        long old = adopted(atMidnight, "rule-ml-policy-v3", "fraud-logistic-v3",
                "fraud-feature-v2", "a".repeat(64), 4);
        evidence(old, "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT", "4",
                """
                {"sourceVersion":"SCN001-002-contract-v1","deviceEventId":"x",
                 "deviceCoverageFactId":"y","deviceTrustFactId":"z"}
                """);
        evidence(old, "RECENT_SECURITY_CHANGE_HIGH_AMOUNT", "4",
                """
                {"sourceVersion":"SCN001-002-contract-v1","passwordEventId":"x",
                 "limitEventId":"y","limitFactId":"z"}
                """);
        evidence(old, "TRANSFER_ABSOLUTE_HIGH_AMOUNT", "3", "{\"observedAmount\":\"10000000\"}");
        complete(old, atMidnight);
        long modern = adopted(late, "rule-ml-policy-v6", "fraud-logistic-v6",
                "fraud-feature-v5", "707284450be52621a7ca00455eab9edfa74fbbef207efbf89ffc87267cd97383", 7);
        evidence(modern, "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND", "1",
                """
                {"sourceVersion":"SCN008-contract-v1","historyCoverageFactId":"x",
                 "stages":[{"sourceTransactionId":"a"},{"sourceTransactionId":"b"}]}
                """);
        complete(modern, late);
        long firstCase = fraudCase("2026-09-10T15:00:00Z", "OPEN");
        long secondCase = fraudCase("2026-09-11T15:00:00Z", "CLOSED");
        link(firstCase, beforeMidnight); link(firstCase, atMidnight);
        link(secondCase, atMidnight); link(secondCase, late);

        mvc.perform(get("/api/v1/fds-dashboard/transactions").param("from", START).param("to", END))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].total").value(1))
                .andExpect(jsonPath("$.days[0].unclassified").value(1))
                .andExpect(jsonPath("$.days[1].total").value(1))
                .andExpect(jsonPath("$.days[1].riskLevels.MEDIUM").value(1))
                .andExpect(jsonPath("$.days[1].scenarios.SCN001").value(1))
                .andExpect(jsonPath("$.days[1].scenarios.SCN002").value(1))
                .andExpect(jsonPath("$.days[2].scenarios.SCN008").value(1))
                .andExpect(jsonPath("$.days[2].total").value(1));
        mvc.perform(get("/api/v1/fds-dashboard/cases").param("from", START).param("to", END))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].newCases").value(0))
                .andExpect(jsonPath("$.days[1].newCases").value(1))
                .andExpect(jsonPath("$.days[2].newCases").value(1))
                .andExpect(jsonPath("$.currentStatuses.OPEN").value(1))
                .andExpect(jsonPath("$.currentStatuses.CLOSED").value(1));
        var range = range();
        assertThat(repository.transactions(range).stream().mapToLong(FdsDashboardRepository.TransactionCount::count).sum())
                .isEqualTo(3);
        assertThat(repository.newCases(range).stream().mapToLong(FdsDashboardRepository.CaseCount::count).sum())
                .isEqualTo(2);
        assertThat(service.transactions(range, Instant.now(), "trace_dashboard_test").days())
                .hasSize(3);
    }

    @Test
    @WithMockUser(authorities = {"transaction:read", "detection:read", "case:read"})
    void mapsAllDedicatedScenarioEvidenceWithoutGuessingFromCommonRules() throws Exception {
        long v2Tx = transaction("2026-09-10T15:00:00Z");
        long v2 = adopted(v2Tx, "rule-ml-policy-v2", "fraud-logistic-v2", "fraud-feature-v1",
                "a".repeat(64), 0);
        evidence(v2, "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT", "1",
                "{\"providerAsOf\":\"2026-09-10T15:00:00Z\",\"lookedUpAt\":\"2026-09-10T15:00:00Z\"}");
        externalEvidence(v2);
        complete(v2, v2Tx);

        long ruleOnlyTx = transaction("2026-09-10T15:00:00Z");
        long ruleOnly = adopted(ruleOnlyTx, "scoring-policy-v3", null, null, null, 0);
        evidence(ruleOnly, "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT", "1",
                "{\"providerAsOf\":\"2026-09-10T15:00:00Z\",\"lookedUpAt\":\"2026-09-10T15:00:00Z\"}");
        externalEvidence(ruleOnly);
        complete(ruleOnly, ruleOnlyTx);

        long v4Tx = transaction("2026-09-10T15:00:01Z");
        long v4 = adopted(v4Tx, "rule-ml-policy-v4", "fraud-logistic-v4", "fraud-feature-v3",
                "259143c159225a276c3c2fc0ed9d60b55926a640b4b4fbcc08c916d87547fc41", 5);
        evidence(v4, "DISTRIBUTED_OUTBOUND_WINDOW", "1",
                "{\"sourceVersion\":\"SCN004-005-contract-v1\",\"coverageFactId\":\"x\"}");
        evidence(v4, "MANY_CUSTOMER_INBOUND_WINDOW", "1",
                "{\"sourceVersion\":\"SCN004-005-contract-v1\",\"coverageFactId\":\"x\"}");
        complete(v4, v4Tx);

        long v5Tx = transaction("2026-09-10T15:00:02Z");
        long v5 = adopted(v5Tx, "rule-ml-policy-v5", "fraud-logistic-v5", "fraud-feature-v4",
                "7d247ee2b86f60eb0e66a5b4731b62b4179b328e226b2df82c9eb52798564eeb", 6);
        evidence(v5, "VERIFIED_INBOUND_ATM_DRAIN", "1",
                "{\"sourceVersion\":\"SCN006-007-contract-v1\",\"sourceTransactionId\":\"x\",\"currentLinkId\":\"y\",\"historyCoverageFactId\":\"z\"}");
        evidence(v5, "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN", "1",
                "{\"sourceVersion\":\"SCN006-007-contract-v1\",\"sourceTransactionId\":\"x\",\"beneficiaryEventId\":\"y\",\"historyCoverageFactId\":\"z\"}");
        complete(v5, v5Tx);

        long nonScenarioTx = transaction("2026-09-10T15:00:03Z");
        long nonScenario = adopted(nonScenarioTx, "rule-ml-policy-v3", "fraud-logistic-v3",
                "fraud-feature-v2", "a".repeat(64), 4);
        evidence(nonScenario, "TRANSFER_ABSOLUTE_HIGH_AMOUNT", "3",
                "{\"observedAmount\":\"10000000\"}");
        complete(nonScenario, nonScenarioTx);

        var days = service.transactions(range(), Instant.now(), "trace_dashboard_test").days();
        var scenarios = days.get(1).scenarios();
        assertThat(scenarios.get("SCN003")).isEqualTo(2);
        for (String code : new String[]{"SCN004", "SCN005", "SCN006", "SCN007"}) {
            assertThat(scenarios.get(code)).as(code).isEqualTo(1);
        }
        assertThat(scenarios.get("SCN001")).isZero();
        assertThat(scenarios.get("SCN002")).isZero();
        assertThat(days.get(1).total()).isEqualTo(5);
        assertThat(days.get(1).classified()).isEqualTo(5);
    }

    @Test
    @WithMockUser(authorities = {"transaction:read", "detection:read", "case:read"})
    void returnsZeroBucketsAndRejectsInvalidRanges() throws Exception {
        mvc.perform(get("/api/v1/fds-dashboard/transactions").param("from", START).param("to", END))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[1].total").value(0));
        mvc.perform(get("/api/v1/fds-dashboard/cases").param("from", END).param("to", START))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fds-dashboard/cases").param("from", START).param("to", "2026-10-13"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fds-dashboard/cases").param("from", START))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fds-dashboard/cases").param("unexpected", "x"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> FdsDashboardRange.parse(Map.of("from", new String[]{START, END},
                "to", new String[]{END}), Instant.now())).isInstanceOf(RuntimeException.class);
    }

    @Test
    @WithMockUser(authorities = "platform:read")
    void platformOnlyIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/fds-dashboard/transactions")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fds-dashboard/cases")).andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/fds-dashboard/transactions")).andExpect(status().isUnauthorized());
    }

    private FdsDashboardRange range() {
        return FdsDashboardRange.parse(Map.of("from", new String[]{START}, "to", new String[]{END}), Instant.now());
    }

    private long transaction(String time) {
        return jdbc.queryForObject("""
                INSERT INTO financial_transaction(transaction_id,transaction_type,amount,currency_code,
                occurred_at,external_customer_ref,sender_account_ref,recipient_account_ref,channel)
                VALUES (?,'ACCOUNT_TRANSFER',100000,'KRW',?,'synthetic-customer','synthetic-sender',
                'synthetic-recipient','MOBILE_BANKING') RETURNING id
                """, Long.class, UUID.randomUUID(), Timestamp.from(Instant.parse(time)));
    }

    private long adopted(long transaction, String policy, String model, String feature,
            String sha, int groups) {
        long result = jdbc.queryForObject("""
                INSERT INTO detection_result(detection_result_id,financial_transaction_id,
                detection_result_version,analysis_status,rule_set_version,scoring_policy_version,
                feature_version,model_version,ml_feature_version,model_sha256,score_group_summary,evaluation_cutoff_at,
                analysis_started_at,analysis_trace_id)
                VALUES (?,?,1,'IN_PROGRESS','synthetic-rule-set',?, 'synthetic-feature',?,?,?,?::jsonb,
                clock_timestamp(),clock_timestamp(),'trace_dashboard_test') RETURNING id
                """, Long.class, UUID.randomUUID(), transaction, policy, model, feature, sha,
                groups == 0 ? null : "[" + String.join(",", java.util.Collections.nCopies(groups, "{}")) + "]");
        return result;
    }

    private void evidence(long result, String code, String version, String json) {
        int order = jdbc.queryForObject("SELECT count(*) FROM detection_evidence WHERE detection_result_id = ?",
                Integer.class, result);
        jdbc.update("""
                INSERT INTO detection_evidence(evidence_id,detection_result_id,evidence_type,
                reason_code,display_description,score_contribution,rule_code,rule_version,
                observation_summary,evidence_occurred_at,sort_order)
                VALUES (?,?,'RULE',?,'Synthetic rule evidence',20,?,?,?::jsonb,clock_timestamp(),?)
                """, UUID.randomUUID(), result, code, code, version, json, order);
    }

    private void complete(long result, long transaction) {
        boolean ruleOnly = jdbc.queryForObject(
                "SELECT model_version IS NULL FROM detection_result WHERE id=?", Boolean.class, result);
        if (ruleOnly) {
            jdbc.update("""
                    UPDATE detection_result SET analysis_status='COMPLETED',risk_score=20,risk_level='MEDIUM',
                    analysis_completed_at=clock_timestamp() WHERE id=?
                    """, result);
        } else {
            jdbc.update("""
                    UPDATE detection_result SET analysis_status='COMPLETED',risk_score=20,risk_level='MEDIUM',
                    analysis_completed_at=clock_timestamp(),rule_risk_score=20,ml_contribution=0,
                    ml_probability_basis_points=0 WHERE id=?
                    """, result);
        }
        jdbc.update("UPDATE financial_transaction SET adopted_detection_result_id=?,risk_level='MEDIUM' WHERE id=?",
                result, transaction);
    }

    private void externalEvidence(long result) {
        int order = jdbc.queryForObject("SELECT count(*) FROM detection_evidence WHERE detection_result_id = ?",
                Integer.class, result);
        jdbc.update("""
                INSERT INTO detection_evidence(evidence_id,detection_result_id,evidence_type,
                reason_code,display_description,observation_summary,evidence_occurred_at,sort_order)
                VALUES (?,?,'EXTERNAL_RISK','SUSPICIOUS_RECIPIENT_ACCOUNT','Synthetic provider match',
                '{"recipientAccountMatched":true}'::jsonb,clock_timestamp(),?)
                """, UUID.randomUUID(), result, order);
    }

    private long fraudCase(String time, String status) {
        Timestamp at = Timestamp.from(Instant.parse(time));
        if (status.equals("CLOSED")) {
            return jdbc.queryForObject("""
                    INSERT INTO fraud_case(case_id,case_status,final_disposition,closed_at,created_at,last_changed_at)
                    VALUES (?,'CLOSED','NORMAL',?,?,?) RETURNING id
                    """, Long.class, UUID.randomUUID(), at, at, at);
        }
        return jdbc.queryForObject("""
                INSERT INTO fraud_case(case_id,case_status,created_at,last_changed_at)
                VALUES (?,'OPEN',?,?) RETURNING id
                """, Long.class, UUID.randomUUID(), at, at);
    }

    private void link(long casePk, long transactionPk) {
        jdbc.update("INSERT INTO case_transaction(fraud_case_id,financial_transaction_id,linked_at) VALUES (?,?,clock_timestamp())",
                casePk, transactionPk);
    }
}
