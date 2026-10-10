ALTER TABLE detection_result DROP CONSTRAINT ck_detection_result_ml_scores;
ALTER TABLE detection_result ADD CONSTRAINT ck_detection_result_ml_scores CHECK (
    (scoring_policy_version NOT IN (
        'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
        AND rule_risk_score IS NULL AND ml_contribution IS NULL
        AND ml_probability_basis_points IS NULL)
    OR (scoring_policy_version IN (
        'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
        AND analysis_status <> 'COMPLETED'
        AND rule_risk_score IS NULL AND ml_contribution IS NULL
        AND ml_probability_basis_points IS NULL)
    OR (scoring_policy_version IN (
        'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
        AND analysis_status = 'COMPLETED'
        AND rule_risk_score BETWEEN 0 AND 100
        AND ml_contribution BETWEEN 0 AND 40
        AND ml_probability_basis_points BETWEEN 0 AND 10000
        AND risk_score = LEAST(100, rule_risk_score + ml_contribution)
        AND risk_level = CASE WHEN risk_score < 20 THEN 'LOW'
            WHEN risk_score < 50 THEN 'MEDIUM'
            WHEN risk_score < 80 THEN 'HIGH' ELSE 'CRITICAL' END
        AND ml_contribution = CASE
            WHEN scoring_policy_version = 'rule-ml-policy-v3'
                THEN LEAST(100 - rule_risk_score,
                    CASE WHEN ml_probability_basis_points <= 5000 THEN 0
                        ELSE ((ml_probability_basis_points - 5000) * 40 + 2500) / 5000 END)
            WHEN ml_probability_basis_points <= 5000 THEN 0
            ELSE ((ml_probability_basis_points - 5000) * 40 + 2500) / 5000 END)
);
