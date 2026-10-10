ALTER TABLE detection_result DROP CONSTRAINT ck_detection_result_ml_identity;
ALTER TABLE detection_result ADD CONSTRAINT ck_detection_result_ml_identity CHECK (
    (scoring_policy_version NOT IN (
        'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
        AND ml_feature_version IS NULL AND model_sha256 IS NULL)
    OR (scoring_policy_version IN ('rule-ml-policy-v1', 'rule-ml-policy-v2')
        AND model_version IS NOT NULL AND ml_feature_version IS NOT NULL
        AND model_sha256 IS NOT NULL
        AND ml_feature_version = 'fraud-feature-v1'
        AND model_sha256 ~ '^[0-9a-f]{64}$')
    OR (scoring_policy_version = 'rule-ml-policy-v3'
        AND model_version IS NOT NULL AND model_version = 'fraud-logistic-v3'
        AND ml_feature_version IS NOT NULL AND model_sha256 IS NOT NULL
        AND ml_feature_version = 'fraud-feature-v2'
        AND model_sha256 ~ '^[0-9a-f]{64}$')
);
