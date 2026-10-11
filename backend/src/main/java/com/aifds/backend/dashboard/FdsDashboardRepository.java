package com.aifds.backend.dashboard;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only aggregate values cross this persistence boundary; no identity or Evidence JSON is returned. */
@Repository
public class FdsDashboardRepository {
    private final JdbcTemplate jdbc;

    public FdsDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TransactionCount(LocalDate date, String riskLevel, long count) { }
    public record ScenarioCount(LocalDate date, String scenario, long count) { }
    public record CaseCount(LocalDate date, long count) { }
    public record StatusCount(String status, long count) { }

    public List<TransactionCount> transactions(FdsDashboardRange range) {
        return jdbc.query("""
                SELECT (ft.occurred_at AT TIME ZONE 'Asia/Seoul')::date AS day,
                       CASE WHEN dr.analysis_status = 'COMPLETED' THEN dr.risk_level
                            ELSE 'UNCLASSIFIED' END AS level,
                       COUNT(*) AS n
                FROM financial_transaction ft
                LEFT JOIN detection_result dr ON dr.id = ft.adopted_detection_result_id
                WHERE ft.occurred_at >= ? AND ft.occurred_at < ?
                GROUP BY day, level
                """, (rs, row) -> new TransactionCount(rs.getDate("day").toLocalDate(),
                        rs.getString("level"), rs.getLong("n")),
                Timestamp.from(range.start()), Timestamp.from(range.end()));
    }

    public List<ScenarioCount> scenarios(FdsDashboardRange range) {
        return jdbc.query("""
                WITH matches AS (
                    SELECT ft.id AS transaction_pk,
                           (ft.occurred_at AT TIME ZONE 'Asia/Seoul')::date AS day,
                           CASE
                             WHEN dr.scoring_policy_version IN ('rule-ml-policy-v3', 'rule-ml-policy-v4',
                                     'rule-ml-policy-v5', 'rule-ml-policy-v6')
                                  AND ev.rule_code = 'RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT'
                                  AND ev.rule_version = '4'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN001-002-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'deviceEventId')
                                  AND jsonb_exists(ev.observation_summary, 'deviceCoverageFactId')
                                  AND jsonb_exists(ev.observation_summary, 'deviceTrustFactId') THEN 'SCN001'
                             WHEN dr.scoring_policy_version IN ('rule-ml-policy-v3', 'rule-ml-policy-v4',
                                     'rule-ml-policy-v5', 'rule-ml-policy-v6')
                                  AND ev.rule_code = 'RECENT_SECURITY_CHANGE_HIGH_AMOUNT'
                                  AND ev.rule_version = '4'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN001-002-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'passwordEventId')
                                  AND jsonb_exists(ev.observation_summary, 'limitEventId')
                                  AND jsonb_exists(ev.observation_summary, 'limitFactId') THEN 'SCN002'
                             WHEN dr.scoring_policy_version IN ('scoring-policy-v3', 'rule-ml-policy-v2', 'rule-ml-policy-v3',
                                     'rule-ml-policy-v4', 'rule-ml-policy-v5', 'rule-ml-policy-v6')
                                  AND ev.rule_code = 'EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT'
                                  AND ev.rule_version = '1'
                                  AND jsonb_exists(ev.observation_summary, 'providerAsOf')
                                  AND jsonb_exists(ev.observation_summary, 'lookedUpAt')
                                  AND EXISTS (SELECT 1 FROM detection_evidence external_ev
                                      WHERE external_ev.detection_result_id = dr.id
                                        AND external_ev.evidence_type = 'EXTERNAL_RISK'
                                        AND external_ev.observation_summary @> '{"recipientAccountMatched":true}'::jsonb)
                                  THEN 'SCN003'
                             WHEN dr.scoring_policy_version IN ('rule-ml-policy-v4', 'rule-ml-policy-v5',
                                     'rule-ml-policy-v6')
                                  AND ev.rule_code = 'DISTRIBUTED_OUTBOUND_WINDOW'
                                  AND ev.rule_version = '1'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN004-005-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'coverageFactId') THEN 'SCN004'
                             WHEN dr.scoring_policy_version IN ('rule-ml-policy-v4', 'rule-ml-policy-v5',
                                     'rule-ml-policy-v6')
                                  AND ev.rule_code = 'MANY_CUSTOMER_INBOUND_WINDOW'
                                  AND ev.rule_version = '1'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN004-005-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'coverageFactId') THEN 'SCN005'
                             WHEN dr.scoring_policy_version IN ('rule-ml-policy-v5', 'rule-ml-policy-v6')
                                  AND ev.rule_code = 'VERIFIED_INBOUND_ATM_DRAIN'
                                  AND ev.rule_version = '1'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN006-007-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'sourceTransactionId')
                                  AND jsonb_exists(ev.observation_summary, 'currentLinkId')
                                  AND jsonb_exists(ev.observation_summary, 'historyCoverageFactId') THEN 'SCN006'
                             WHEN dr.scoring_policy_version IN ('rule-ml-policy-v5', 'rule-ml-policy-v6')
                                  AND ev.rule_code = 'VERIFIED_LOAN_NEW_RECIPIENT_DRAIN'
                                  AND ev.rule_version = '1'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN006-007-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'sourceTransactionId')
                                  AND jsonb_exists(ev.observation_summary, 'beneficiaryEventId')
                                  AND jsonb_exists(ev.observation_summary, 'historyCoverageFactId') THEN 'SCN007'
                             WHEN dr.scoring_policy_version = 'rule-ml-policy-v6'
                                  AND ev.rule_code = 'VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND'
                                  AND ev.rule_version = '1'
                                  AND ev.observation_summary @> '{"sourceVersion":"SCN008-contract-v1"}'::jsonb
                                  AND jsonb_exists(ev.observation_summary, 'historyCoverageFactId')
                                  AND CASE WHEN jsonb_typeof(ev.observation_summary -> 'stages') = 'array'
                                      THEN jsonb_array_length(ev.observation_summary -> 'stages') >= 2
                                      ELSE false END
                                  THEN 'SCN008'
                           END AS scenario
                    FROM financial_transaction ft
                    JOIN detection_result dr ON dr.id = ft.adopted_detection_result_id
                        AND dr.analysis_status = 'COMPLETED'
                    JOIN detection_evidence ev ON ev.detection_result_id = dr.id
                        AND ev.evidence_type = 'RULE'
                    WHERE ft.occurred_at >= ? AND ft.occurred_at < ?
                )
                SELECT day, scenario, COUNT(DISTINCT transaction_pk) AS n
                FROM matches WHERE scenario IS NOT NULL
                GROUP BY day, scenario
                """, (rs, row) -> new ScenarioCount(rs.getDate("day").toLocalDate(),
                        rs.getString("scenario"), rs.getLong("n")),
                Timestamp.from(range.start()), Timestamp.from(range.end()));
    }

    public List<CaseCount> newCases(FdsDashboardRange range) {
        return jdbc.query("""
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day, COUNT(*) AS n
                FROM fraud_case WHERE created_at >= ? AND created_at < ?
                GROUP BY day
                """, (rs, row) -> new CaseCount(rs.getDate("day").toLocalDate(), rs.getLong("n")),
                Timestamp.from(range.start()), Timestamp.from(range.end()));
    }

    public List<StatusCount> currentStatuses(FdsDashboardRange range) {
        return jdbc.query("""
                SELECT case_status, COUNT(*) AS n FROM fraud_case
                WHERE created_at >= ? AND created_at < ? GROUP BY case_status
                """, (rs, row) -> new StatusCount(rs.getString("case_status"), rs.getLong("n")),
                Timestamp.from(range.start()), Timestamp.from(range.end()));
    }
}
