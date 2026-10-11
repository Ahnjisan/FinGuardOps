package com.aifds.backend.detection.service;

import com.aifds.backend.detection.dto.AdoptedDetectionResultItemResponse;
import com.aifds.backend.detection.dto.AdoptedDetectionResultResponse;
import com.aifds.backend.detection.dto.AdoptedRuleEvidenceResponse;
import com.aifds.backend.detection.dto.AdoptedMlEvidenceResponse;
import com.aifds.backend.detection.dto.Scn003EvidenceResponse;
import com.aifds.backend.detection.dto.Scn001002EvidenceResponse;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidenceType;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.ml.MlDetectionPolicy;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.exception.TransactionQueryTimeoutException;
import com.aifds.backend.transaction.exception.TransactionQueryUnavailableException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.aifds.backend.transaction.validation.TransactionQueryValidator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Service
// Transaction, latest analysis and RULE rows must come from one PostgreSQL snapshot.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdoptedDetectionResultQueryService {
    private final TransactionQueryValidator validator;
    private final FinancialTransactionRepository transactions;
    private final DetectionResultRepository results;
    private final DetectionEvidenceRepository evidence;

    public AdoptedDetectionResultQueryService(
            TransactionQueryValidator validator,
            FinancialTransactionRepository transactions,
            DetectionResultRepository results,
            DetectionEvidenceRepository evidence
    ) {
        this.validator = validator;
        this.transactions = transactions;
        this.results = results;
        this.evidence = evidence;
    }

    public AdoptedDetectionResultResponse find(String rawTransactionId) {
        UUID transactionId = validator.validateTransactionId(rawTransactionId);
        try {
            FinancialTransaction transaction = transactions.findByTransactionId(transactionId)
                    .orElseThrow(TransactionNotFoundException::new);
            DetectionResult latest = results
                    .findFirstByFinancialTransaction_IdOrderByDetectionResultVersionDesc(transaction.getId())
                    .orElse(null);
            DetectionResult adopted = transaction.getAdoptedDetectionResult();
            if (adopted != null && (latest == null
                    || adopted.getDetectionResultVersion() > latest.getDetectionResultVersion())) {
                throw new IllegalStateException("Adopted detection result version is inconsistent");
            }
            String availability = adopted == null
                    ? latest == null ? "NO_HISTORY" : switch (latest.getAnalysisStatus()) {
                        case PENDING -> "PENDING";
                        case IN_PROGRESS -> "IN_PROGRESS";
                        case FAILED -> "FAILED";
                        case COMPLETED -> "COMPLETED_NOT_ADOPTED";
                    }
                    : "AVAILABLE";
            AdoptedDetectionResultItemResponse item = adopted == null ? null : project(transaction, adopted);
            return new AdoptedDetectionResultResponse(
                    transactionId,
                    availability,
                    latest == null ? null : latest.getDetectionResultVersion(),
                    latest == null ? null : latest.getAnalysisStatus().name(),
                    item,
                    latest != null && latest.getAnalysisStatus() == DetectionAnalysisStatus.FAILED
                            && latest.getFailureCode() != null
                            && latest.getFailureCode().startsWith("ML_")
                            ? latest.getFailureCode() : null
            );
        } catch (DataAccessException exception) {
            if (hasCause(exception, QueryTimeoutException.class)) {
                throw new TransactionQueryTimeoutException(exception);
            }
            if (hasCause(exception, TransientDataAccessResourceException.class)
                    || hasCause(exception, DataAccessResourceFailureException.class)) {
                throw new TransactionQueryUnavailableException(exception);
            }
            throw exception;
        }
    }

    private AdoptedDetectionResultItemResponse project(
            FinancialTransaction transaction, DetectionResult adopted
    ) {
        if (!adopted.belongsTo(transaction)
                || adopted.getAnalysisStatus() != DetectionAnalysisStatus.COMPLETED
                || adopted.getRiskLevel() != transaction.getRiskLevel()
                || adopted.getRiskScore() == null
                || adopted.getAnalysisCompletedAt() == null) {
            throw new IllegalStateException("Adopted detection result is inconsistent");
        }
        List<AdoptedRuleEvidenceResponse> rules = evidence
                .findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                        adopted.getId(), DetectionEvidenceType.RULE)
                .stream()
                .map(row -> new AdoptedRuleEvidenceResponse(
                        row.getRuleCode(), row.getRuleVersion(),
                        row.getReasonCode(), row.getScoreContribution(),
                        row.getAppliedContribution()))
                .toList();
        var mlRows = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                adopted.getId(), DetectionEvidenceType.ML);
        List<AdoptedMlEvidenceResponse> ml = mlRows.stream()
                .map(row -> new AdoptedMlEvidenceResponse(row.getReasonCode(),
                        row.getScoreContribution(),
                        row.getObservationSummary().path("probabilityBasisPoints").asInt(-1)))
                .toList();
        boolean applied = adopted.getMlContribution() != null;
        if (applied) {
            if (ml.size() != 1 || adopted.getRuleRiskScore() == null
                    || adopted.getMlProbabilityBasisPoints() == null
                    || (("rule-ml-policy-v3".equals(adopted.getScoringPolicyVersion())
                            || "rule-ml-policy-v4".equals(adopted.getScoringPolicyVersion())
                            || "rule-ml-policy-v5".equals(adopted.getScoringPolicyVersion()))
                            ? mlRows.get(0).getAppliedContribution() == null
                                    || mlRows.get(0).getAppliedContribution() != adopted.getMlContribution()
                            : ml.get(0).scoreContribution() != adopted.getMlContribution())
                    || ml.get(0).probabilityBasisPoints() != adopted.getMlProbabilityBasisPoints()
                    || adopted.getRiskScore() != Math.min(100,
                        adopted.getRuleRiskScore() + adopted.getMlContribution())
                    || adopted.getRiskLevel() != MlDetectionPolicy.riskLevel(
                        adopted.getRiskScore())) {
                throw new IllegalStateException("Adopted ML result is inconsistent");
            }
        } else if (!ml.isEmpty()) {
            throw new IllegalStateException("Rule-only result has ML evidence");
        }
        Scn003EvidenceResponse scn003 = null;
        Scn001002EvidenceResponse scn001002 = null;
        com.fasterxml.jackson.databind.JsonNode aggregation = null;
        com.fasterxml.jackson.databind.JsonNode fundFlow = null;
        if ("scoring-policy-v3".equals(adopted.getScoringPolicyVersion())
                || MlDetectionPolicy.SCN003_POLICY_VERSION.equals(
                        adopted.getScoringPolicyVersion())) {
            var external = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                    adopted.getId(), DetectionEvidenceType.EXTERNAL_RISK);
            var history = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                    adopted.getId(), DetectionEvidenceType.BEHAVIOR_PATTERN);
            if (external.size() != 1 || history.size() != 1) {
                throw new IllegalStateException("SCN-003 Evidence is incomplete");
            }
            var source = external.get(0).getObservationSummary();
            var prior = history.get(0).getObservationSummary();
            boolean matched = source.path("recipientAccountMatched").asBoolean(false);
            long r005Count = rules.stream().filter(row ->
                    RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT.equals(
                            row.ruleCode())).count();
            if (r005Count != (matched ? 1 : 0)
                    || !"SCN003-contract-v1".equals(source.path("sourceVersion").asText())
                    || !"SCN003-contract-v1".equals(prior.path("sourceVersion").asText())
                    || !prior.path("priorApprovedRecipientTransferObserved").isBoolean()) {
                throw new IllegalStateException("SCN-003 Evidence contradicts R005");
            }
            scn003 = new Scn003EvidenceResponse("SCN003-contract-v1",
                    source.path("providerCode").asText(),
                    Instant.parse(source.path("providerAsOf").asText()),
                    Instant.parse(source.path("lookedUpAt").asText()),
                    matched, prior.path("priorApprovedRecipientTransferObserved").booleanValue());
        }
        if ("rule-ml-policy-v3".equals(adopted.getScoringPolicyVersion())
                || "rule-ml-policy-v4".equals(adopted.getScoringPolicyVersion())
                || "rule-ml-policy-v5".equals(adopted.getScoringPolicyVersion())) {
            var external = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                    adopted.getId(), DetectionEvidenceType.EXTERNAL_RISK);
            var history = evidence.findAllByDetectionResult_IdAndEvidenceTypeOrderBySortOrderAscIdAsc(
                    adopted.getId(), DetectionEvidenceType.BEHAVIOR_PATTERN);
            var scenarioHistory = history.stream().filter(row ->
                    "SCENARIO_HISTORY_CONTEXT".equals(row.getReasonCode())).toList();
            var aggregateHistory = history.stream().filter(row ->
                    "AGGREGATION_HISTORY_CONTEXT".equals(row.getReasonCode())).toList();
            var fundHistory = history.stream().filter(row ->
                    "FUND_FLOW_HISTORY_CONTEXT".equals(row.getReasonCode())).toList();
            boolean isFundFlow = "rule-ml-policy-v5".equals(adopted.getScoringPolicyVersion());
            boolean isAggregation = isFundFlow
                    || "rule-ml-policy-v4".equals(adopted.getScoringPolicyVersion());
            if (external.size() != 1 || scenarioHistory.size() != 1
                    || aggregateHistory.size() != (isAggregation ? 1 : 0)
                    || fundHistory.size() != (isFundFlow ? 1 : 0)
                    || history.size() != (isFundFlow ? 3 : isAggregation ? 2 : 1)
                    || adopted.getScoreGroupSummary() == null
                    || rules.stream().anyMatch(row -> row.appliedContribution() == null)
                    || rules.stream().mapToInt(AdoptedRuleEvidenceResponse::appliedContribution).sum()
                            != adopted.getRuleRiskScore()) {
                throw new IllegalStateException("SCN-001/002 Evidence attribution is inconsistent");
            }
            var source = external.get(0).getObservationSummary();
            var prior = scenarioHistory.get(0).getObservationSummary();
            if (!"SCN001-002-contract-v1".equals(source.path("sourceVersion").asText())
                    || !"SCN001-002-contract-v1".equals(prior.path("sourceVersion").asText())) {
                throw new IllegalStateException("SCN-001/002 Evidence source is inconsistent");
            }
            boolean recipientMatched = source.path("recipientAccountMatched").asBoolean(false);
            boolean deviceMatched = source.path("exactDeviceMatched").asBoolean(false);
            boolean r005 = rules.stream().anyMatch(row ->
                    RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT
                            .equals(row.ruleCode()));
            boolean r007 = rules.stream().anyMatch(row ->
                    RuleV1ContractRegistry.EXTERNAL_RISK_DEVICE_MATCH
                            .equals(row.ruleCode()));
            if (recipientMatched != r005 || deviceMatched != r007) {
                throw new IllegalStateException("SCN-001/002 External Risk Evidence is inconsistent");
            }
            scn001002 = new Scn001002EvidenceResponse("SCN001-002-contract-v1",
                    prior.path("baselineStatus").asText(),
                    prior.path("baselineCount").asLong(), prior.path("baselineDays").asLong(),
                    prior.path("recipientStatus").asText(), prior.path("deviceStatus").asText(),
                    source.path("providerCode").asText(),
                    Instant.parse(source.path("providerAsOf").asText()),
                    Instant.parse(source.path("lookedUpAt").asText()),
                    source.path("recipientAccountMatched").asBoolean(),
                    source.path("exactDeviceMatched").asBoolean());
            if (isAggregation) {
                aggregation = aggregateHistory.get(0).getObservationSummary();
                if (!"SCN004-005-contract-v1".equals(
                        aggregation.path("sourceVersion").asText())
                        || !"LOCAL_FDS_MOCK".equals(
                                aggregation.path("mockSourceCode").asText())
                        || !aggregation.path("outbound").isObject()
                        || !aggregation.path("inbound").isObject()
                        || !aggregation.path("evaluationCutoffAt").isTextual()) {
                    throw new IllegalStateException("Aggregation Evidence is incomplete");
                }
                for (var entry : java.util.Map.of(
                        RuleV1ContractRegistry.DISTRIBUTED_OUTBOUND_WINDOW, "outbound",
                        RuleV1ContractRegistry.MANY_CUSTOMER_INBOUND_WINDOW, "inbound")
                        .entrySet()) {
                    var window = aggregation.path(entry.getValue());
                    long threshold = "outbound".equals(entry.getValue()) ? 3_000_000 : 5_000_000;
                    boolean expected = "AVAILABLE".equals(window.path("status").asText())
                            && window.path("transferCount").asLong(-1) >= 3
                            && window.path("distinctCount").asLong(-1) >= 3
                            && new java.math.BigDecimal(window.path("totalAmount").asText("0"))
                                    .compareTo(java.math.BigDecimal.valueOf(threshold)) >= 0;
                    if (rules.stream().anyMatch(row -> entry.getKey().equals(row.ruleCode()))
                            != expected) {
                        throw new IllegalStateException("Aggregation Evidence contradicts Rule");
                    }
                }
            }
            if (isFundFlow) {
                fundFlow = fundHistory.get(0).getObservationSummary();
                if (!"SCN006-007-contract-v1".equals(
                        fundFlow.path("sourceVersion").asText())
                        || !"LOCAL_FDS_MOCK".equals(
                                fundFlow.path("mockSourceCode").asText())
                        || !"v2".equals(fundFlow.path("mockSourceVersion").asText())
                        || !fundFlow.path("atm").isObject()
                        || !fundFlow.path("loan").isObject()) {
                    throw new IllegalStateException("Fund flow Evidence is incomplete");
                }
                for (var entry : java.util.Map.of(
                        RuleV1ContractRegistry.VERIFIED_INBOUND_ATM_DRAIN, "atm",
                        RuleV1ContractRegistry.VERIFIED_LOAN_NEW_RECIPIENT_DRAIN, "loan")
                        .entrySet()) {
                    var flow = fundFlow.path(entry.getValue());
                    boolean expected = "AVAILABLE".equals(flow.path("status").asText())
                            && flow.path("ratioBasisPoints").asInt(-1) >= 8000;
                    if (rules.stream().anyMatch(row -> entry.getKey().equals(row.ruleCode()))
                            != expected) {
                        throw new IllegalStateException("Fund flow Evidence contradicts Rule");
                    }
                }
            }
        }
        return new AdoptedDetectionResultItemResponse(
                adopted.getDetectionResultId(), adopted.getDetectionResultVersion(),
                adopted.getRiskLevel().name(), adopted.getRiskScore(),
                adopted.getAnalysisCompletedAt(), adopted.getRuleSetVersion(),
                adopted.getScoringPolicyVersion(), rules,
                applied ? adopted.getRuleRiskScore() : adopted.getRiskScore(),
                adopted.getMlContribution(), applied ? "APPLIED" : "RULE_ONLY",
                applied ? adopted.getModelVersion() : null,
                adopted.getMlFeatureVersion(), adopted.getModelSha256(), ml, scn003,
                adopted.getScoreGroupSummary(),
                ("rule-ml-policy-v3".equals(adopted.getScoringPolicyVersion())
                        || "rule-ml-policy-v4".equals(adopted.getScoringPolicyVersion())
                        || "rule-ml-policy-v5".equals(adopted.getScoringPolicyVersion()))
                        ? mlRows.get(0).getScoreContribution() : null, scn001002,
                aggregation, fundFlow
        );
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        for (int i = 0; i < 32 && current != null; i++) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
