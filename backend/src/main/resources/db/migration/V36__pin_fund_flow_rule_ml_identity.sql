ALTER TABLE scenario_case_correlation
    DROP CONSTRAINT scenario_case_correlation_scenario_code_check;
ALTER TABLE scenario_case_correlation
    ADD CONSTRAINT scenario_case_correlation_scenario_code_check
    CHECK (scenario_code IN ('SCN001','SCN002','SCN004','SCN005','SCN006','SCN007'));

ALTER TABLE detection_result DROP CONSTRAINT ck_detection_result_ml_identity;
ALTER TABLE detection_result ADD CONSTRAINT ck_detection_result_ml_identity CHECK (
    (scoring_policy_version NOT IN (
        'rule-ml-policy-v1','rule-ml-policy-v2','rule-ml-policy-v3',
        'rule-ml-policy-v4','rule-ml-policy-v5')
        AND ml_feature_version IS NULL AND model_sha256 IS NULL)
    OR (scoring_policy_version IN ('rule-ml-policy-v1','rule-ml-policy-v2')
        AND model_version IS NOT NULL AND ml_feature_version IS NOT NULL
        AND model_sha256 IS NOT NULL
        AND ml_feature_version = 'fraud-feature-v1'
        AND model_sha256 ~ '^[0-9a-f]{64}$')
    OR (scoring_policy_version = 'rule-ml-policy-v3'
        AND model_version IS NOT NULL AND ml_feature_version IS NOT NULL
        AND model_sha256 IS NOT NULL AND model_version = 'fraud-logistic-v3'
        AND ml_feature_version = 'fraud-feature-v2'
        AND model_sha256 ~ '^[0-9a-f]{64}$')
    OR (scoring_policy_version = 'rule-ml-policy-v4'
        AND model_version IS NOT NULL AND ml_feature_version IS NOT NULL
        AND model_sha256 IS NOT NULL AND model_version = 'fraud-logistic-v4'
        AND ml_feature_version = 'fraud-feature-v3'
        AND model_sha256 = '259143c159225a276c3c2fc0ed9d60b55926a640b4b4fbcc08c916d87547fc41')
    OR (scoring_policy_version = 'rule-ml-policy-v5'
        AND model_version IS NOT NULL AND ml_feature_version IS NOT NULL
        AND model_sha256 IS NOT NULL AND model_version = 'fraud-logistic-v5'
        AND ml_feature_version = 'fraud-feature-v4'
        AND model_sha256 = '7d247ee2b86f60eb0e66a5b4731b62b4179b328e226b2df82c9eb52798564eeb')
);

ALTER TABLE detection_result DROP CONSTRAINT ck_detection_result_v4_groups;
ALTER TABLE detection_result ADD CONSTRAINT ck_detection_result_v4_groups CHECK (
    (scoring_policy_version = 'rule-ml-policy-v3' AND
        (analysis_status <> 'COMPLETED' OR (score_group_summary IS NOT NULL
            AND jsonb_typeof(score_group_summary) = 'array'
            AND jsonb_array_length(score_group_summary) = 4)))
    OR (scoring_policy_version = 'rule-ml-policy-v4' AND
        (analysis_status <> 'COMPLETED' OR (score_group_summary IS NOT NULL
            AND jsonb_typeof(score_group_summary) = 'array'
            AND jsonb_array_length(score_group_summary) = 5)))
    OR (scoring_policy_version = 'rule-ml-policy-v5' AND
        (analysis_status <> 'COMPLETED' OR (score_group_summary IS NOT NULL
            AND jsonb_typeof(score_group_summary) = 'array'
            AND jsonb_array_length(score_group_summary) = 6)))
    OR (scoring_policy_version NOT IN (
        'rule-ml-policy-v3','rule-ml-policy-v4','rule-ml-policy-v5')
        AND score_group_summary IS NULL)
);

ALTER TABLE detection_result DROP CONSTRAINT ck_detection_result_ml_scores;
ALTER TABLE detection_result ADD CONSTRAINT ck_detection_result_ml_scores CHECK (
    (scoring_policy_version NOT IN (
        'rule-ml-policy-v1','rule-ml-policy-v2','rule-ml-policy-v3',
        'rule-ml-policy-v4','rule-ml-policy-v5')
        AND rule_risk_score IS NULL AND ml_contribution IS NULL
        AND ml_probability_basis_points IS NULL)
    OR (scoring_policy_version IN (
        'rule-ml-policy-v1','rule-ml-policy-v2','rule-ml-policy-v3',
        'rule-ml-policy-v4','rule-ml-policy-v5')
        AND analysis_status <> 'COMPLETED'
        AND rule_risk_score IS NULL AND ml_contribution IS NULL
        AND ml_probability_basis_points IS NULL)
    OR (scoring_policy_version IN (
        'rule-ml-policy-v1','rule-ml-policy-v2','rule-ml-policy-v3',
        'rule-ml-policy-v4','rule-ml-policy-v5')
        AND analysis_status = 'COMPLETED'
        AND rule_risk_score IS NOT NULL AND ml_contribution IS NOT NULL
        AND ml_probability_basis_points IS NOT NULL
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
            WHEN scoring_policy_version IN ('rule-ml-policy-v4','rule-ml-policy-v5')
                THEN ml_contribution
            WHEN ml_probability_basis_points <= 5000 THEN 0
            ELSE ((ml_probability_basis_points - 5000) * 40 + 2500) / 5000 END
        AND (scoring_policy_version NOT IN ('rule-ml-policy-v4','rule-ml-policy-v5')
            OR ml_contribution <= LEAST(100 - rule_risk_score,
                CASE WHEN ml_probability_basis_points <= 5000 THEN 0
                    ELSE ((ml_probability_basis_points - 5000) * 40 + 2500) / 5000 END)))
);
