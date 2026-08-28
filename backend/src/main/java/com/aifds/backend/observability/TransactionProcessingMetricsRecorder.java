package com.aifds.backend.observability;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.time.Duration;

public interface TransactionProcessingMetricsRecorder {

    enum IntakeOutcome {
        ACCEPTED("accepted"),
        VALIDATION_REJECTED("validation_rejected"),
        DEPENDENCY_UNAVAILABLE("dependency_unavailable"),
        EXTERNAL_RISK_FAILED("external_risk_failed"),
        RULE_FAILED("rule_failed"),
        FINALIZATION_FAILED("finalization_failed"),
        COMPLETION_FAILED("completion_failed"),
        IDEMPOTENT_REPLAY("idempotent_replay"),
        IN_PROGRESS("in_progress"),
        CONFLICT("conflict"),
        INTERNAL_FAILURE("internal_failure");

        private final String tagValue;

        IntakeOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    enum DuplicateResult {
        IN_PROGRESS("in_progress"),
        COMPLETED("completed"),
        FAILED("failed");

        private final String tagValue;

        DuplicateResult(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    enum ExternalRiskResult {
        MATCHED("matched"),
        UNMATCHED("unmatched"),
        FAILED("failed");

        private final String tagValue;

        ExternalRiskResult(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    enum RuleResult {
        COMPLETED("completed"),
        FAILED("failed");

        private final String tagValue;

        RuleResult(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    enum FailureCategory {
        NONE("none"),
        UNKNOWN("unknown"),
        TIMEOUT("TIMEOUT"),
        UNAVAILABLE("UNAVAILABLE"),
        INVALID_REQUEST("INVALID_REQUEST"),
        UNSUPPORTED_CAPABILITY("UNSUPPORTED_CAPABILITY"),
        INVALID_RESPONSE("INVALID_RESPONSE"),
        TRANSFORMATION_ERROR("TRANSFORMATION_ERROR"),
        RULE_ANALYSIS_START_FAILED("RULE_ANALYSIS_START_FAILED"),
        RULE_ANALYSIS_HTTP_CALL_FAILED("RULE_ANALYSIS_HTTP_CALL_FAILED"),
        RULE_ANALYSIS_RESPONSE_MAPPING_FAILED(
                "RULE_ANALYSIS_RESPONSE_MAPPING_FAILED"
        ),
        RULE_ANALYSIS_ADOPTION_FAILED("RULE_ANALYSIS_ADOPTION_FAILED"),
        RULE_ANALYSIS_TRANSACTION_BOUNDARY_VIOLATION(
                "RULE_ANALYSIS_TRANSACTION_BOUNDARY_VIOLATION"
        ),
        AI_SERVICE_REQUEST_CONTRACT_ERROR(
                "AI_SERVICE_REQUEST_CONTRACT_ERROR"
        ),
        AI_SERVICE_PAYLOAD_TOO_LARGE("AI_SERVICE_PAYLOAD_TOO_LARGE"),
        AI_SERVICE_RULE_CONTRACT_ERROR("AI_SERVICE_RULE_CONTRACT_ERROR"),
        AI_SERVICE_CAPABILITY_MISMATCH("AI_SERVICE_CAPABILITY_MISMATCH"),
        AI_SERVICE_INTERNAL_ERROR("AI_SERVICE_INTERNAL_ERROR"),
        AI_SERVICE_CONNECT_TIMEOUT("AI_SERVICE_CONNECT_TIMEOUT"),
        AI_SERVICE_RESPONSE_TIMEOUT("AI_SERVICE_RESPONSE_TIMEOUT"),
        AI_SERVICE_UNAVAILABLE("AI_SERVICE_UNAVAILABLE"),
        AI_SERVICE_INVALID_RESPONSE("AI_SERVICE_INVALID_RESPONSE");

        private final String tagValue;

        FailureCategory(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }

        public static FailureCategory fromExternalRisk(
                ExternalRiskFailureCategory category
        ) {
            if (category == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(category.name());
            } catch (IllegalArgumentException exception) {
                return UNKNOWN;
            }
        }

        public static FailureCategory fromRule(String category) {
            if (category == null) {
                return UNKNOWN;
            }
            try {
                FailureCategory mapped = valueOf(category);
                return mapped == NONE ? UNKNOWN : mapped;
            } catch (IllegalArgumentException exception) {
                return UNKNOWN;
            }
        }
    }

    void recordIntakeOutcome(IntakeOutcome outcome);

    void recordTransactionReceived();

    void recordTransactionTerminal(
            TransactionProcessingStatus status,
            RiskLevel riskLevel,
            FailureCategory failureCategory,
            Duration processingDuration
    );

    void recordDuplicateRequest(DuplicateResult result);

    void recordIdempotencyConflict();

    void recordExternalRisk(
            ExternalRiskResult result,
            FailureCategory failureCategory,
            Duration duration
    );

    void recordRuleAnalysis(
            RuleResult result,
            RiskLevel riskLevel,
            FailureCategory failureCategory,
            Duration duration
    );

    static TransactionProcessingMetricsRecorder noop() {
        return NoOp.INSTANCE;
    }

    enum NoOp implements TransactionProcessingMetricsRecorder {
        INSTANCE;

        @Override
        public void recordIntakeOutcome(IntakeOutcome outcome) {
        }

        @Override
        public void recordTransactionReceived() {
        }

        @Override
        public void recordTransactionTerminal(
                TransactionProcessingStatus status,
                RiskLevel riskLevel,
                FailureCategory failureCategory,
                Duration processingDuration
        ) {
        }

        @Override
        public void recordDuplicateRequest(DuplicateResult result) {
        }

        @Override
        public void recordIdempotencyConflict() {
        }

        @Override
        public void recordExternalRisk(
                ExternalRiskResult result,
                FailureCategory failureCategory,
                Duration duration
        ) {
        }

        @Override
        public void recordRuleAnalysis(
                RuleResult result,
                RiskLevel riskLevel,
                FailureCategory failureCategory,
                Duration duration
        ) {
        }
    }
}
