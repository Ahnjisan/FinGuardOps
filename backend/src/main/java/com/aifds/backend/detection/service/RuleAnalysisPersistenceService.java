package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.DetectionAnalysisStatus;
import com.aifds.backend.detection.entity.DetectionEvidence;
import com.aifds.backend.detection.entity.DetectionResult;
import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.detection.ml.MlDetectionPolicy;
import com.aifds.backend.detection.ml.MlDetectionService;
import com.aifds.backend.detection.repository.DetectionEvidenceRepository;
import com.aifds.backend.detection.repository.DetectionResultRepository;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.idempotency.service.TransactionIntakeMaintenanceGate;
import com.aifds.backend.observability.TransactionProcessingMetricsRecorder;
import com.aifds.backend.rule.client.RuleAnalysisRequestV2Mapper;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV3;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV4;
import com.aifds.backend.rule.client.dto.RuleAnalysisResponse;
import com.aifds.backend.scenario.ScenarioRuleResponseValidator;
import com.aifds.backend.scenario.ScenarioAggregationRuleResponseValidator;
import com.aifds.backend.scenario.ScenarioAggregationRulePolicy;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.aifds.backend.rule.client.dto.ExternalRiskSnapshotRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.rule.contract.RuleV1ContractRegistry;
import com.aifds.backend.rule.entity.RuleVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.scenario.ScenarioFacts;
import com.aifds.backend.scenario.ScenarioFactsService;
import com.aifds.backend.scenario.ScenarioAggregationFacts;
import com.aifds.backend.scenario.ScenarioAggregationFactsService;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;
import com.aifds.backend.transaction.exception.TransactionNotFoundException;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RuleAnalysisPersistenceService {

    private ScenarioFactsService scenarioFactsService;
    private ScenarioAggregationFactsService aggregationFactsService;

    @Autowired(required = false)
    void setScenarioFactsService(ScenarioFactsService scenarioFactsService) {
        this.scenarioFactsService = scenarioFactsService;
    }

    @Autowired(required = false)
    void setAggregationFactsService(ScenarioAggregationFactsService aggregationFactsService) {
        this.aggregationFactsService = aggregationFactsService;
    }

    private final FinancialTransactionRepository transactionRepository;
    private final DetectionResultRepository detectionResultRepository;
    private final DetectionEvidenceRepository evidenceRepository;
    private final RuleVersionRepository ruleVersionRepository;
    private final RuleAnalysisSnapshotAssembler snapshotAssembler;
    private final RuleAnalysisRequestV2Mapper requestV2Mapper;
    private final TransactionProcessingMetricsRecorder metricsRecorder;
    private final TransactionIntakeMaintenanceGate maintenanceGate;

    @Autowired
    public RuleAnalysisPersistenceService(
            FinancialTransactionRepository transactionRepository,
            DetectionResultRepository detectionResultRepository,
            DetectionEvidenceRepository evidenceRepository,
            RuleVersionRepository ruleVersionRepository,
            RuleAnalysisSnapshotAssembler snapshotAssembler,
            RuleAnalysisRequestV2Mapper requestV2Mapper,
            TransactionProcessingMetricsRecorder metricsRecorder,
            TransactionIntakeMaintenanceGate maintenanceGate
    ) {
        this.transactionRepository = transactionRepository;
        this.detectionResultRepository = detectionResultRepository;
        this.evidenceRepository = evidenceRepository;
        this.ruleVersionRepository = ruleVersionRepository;
        this.snapshotAssembler = snapshotAssembler;
        this.requestV2Mapper = requestV2Mapper;
        this.metricsRecorder = metricsRecorder == null
                ? TransactionProcessingMetricsRecorder.noop()
                : metricsRecorder;
        this.maintenanceGate = maintenanceGate;
    }

    public RuleAnalysisPersistenceService(
            FinancialTransactionRepository transactionRepository,
            DetectionResultRepository detectionResultRepository,
            DetectionEvidenceRepository evidenceRepository,
            RuleVersionRepository ruleVersionRepository,
            RuleAnalysisSnapshotAssembler snapshotAssembler,
            RuleAnalysisRequestV2Mapper requestV2Mapper
    ) {
        this(
                transactionRepository,
                detectionResultRepository,
                evidenceRepository,
                ruleVersionRepository,
                snapshotAssembler,
                requestV2Mapper,
                TransactionProcessingMetricsRecorder.noop(),
                null
        );
    }

    public RuleAnalysisPersistenceService(
            FinancialTransactionRepository transactionRepository,
            DetectionResultRepository detectionResultRepository,
            DetectionEvidenceRepository evidenceRepository,
            RuleVersionRepository ruleVersionRepository,
            RuleAnalysisSnapshotAssembler snapshotAssembler,
            RuleAnalysisRequestV2Mapper requestV2Mapper,
            TransactionProcessingMetricsRecorder metricsRecorder
    ) {
        this(transactionRepository, detectionResultRepository,
                evidenceRepository, ruleVersionRepository, snapshotAssembler,
                requestV2Mapper, metricsRecorder, null);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ
    )
    public StartedRuleAnalysisExecution startAnalysis(
            UUID transactionId,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            String analysisTraceId,
            Instant startedAt
    ) {
        requireOpenGate();
        PreparedRuleAnalysisStart prepared = prepareStart(transactionId, false);
        StartedRuleAnalysis started = persistStart(
                prepared,
                scoringPolicyVersion,
                featureVersion,
                modelVersion,
                analysisTraceId,
                startedAt
        );
        return new StartedRuleAnalysisExecution(
                started,
                prepared.snapshot().request()
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ
    )
    public StartedRuleAnalysisV2Execution startAnalysisV2(
            UUID transactionId,
            ExternalRiskSnapshot externalRiskSnapshot,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            String analysisTraceId,
            Instant startedAt
    ) {
        requireOpenGate();
        PreparedRuleAnalysisStart prepared = prepareStart(transactionId, modelVersion != null);
        RuleAnalysisRequestV2 request = requestV2Mapper.map(
                prepared.snapshot().request(),
                externalRiskSnapshot
        );
        int selectedPolicy = RulePolicyVersion.from(request.ruleVersions());
        if (selectedPolicy >= 4 && modelVersion == null) {
            throw new IllegalStateException("Scenario Rule requires a pinned ML model");
        }
        if (selectedPolicy >= 4) {
            modelVersion = selectedPolicy == 5
                    ? MlDetectionPolicy.SCN004_005_MODEL_VERSION
                    : MlDetectionPolicy.SCN001_002_MODEL_VERSION;
        }
        ScenarioFacts scenarioFacts = selectedPolicy >= 4
                ? Objects.requireNonNull(scenarioFactsService,
                        "ScenarioFactsService is required for scenario Rule")
                        .snapshot(prepared.transaction(), externalRiskSnapshot)
                : null;
        ScenarioAggregationFacts aggregationFacts = selectedPolicy == 5
                ? Objects.requireNonNull(aggregationFactsService,
                        "ScenarioAggregationFactsService is required for Rule v5")
                        .snapshot(prepared.transaction())
                : null;
        StartedRuleAnalysis started = persistStart(
                prepared,
                scoringPolicyVersion,
                featureVersion,
                modelVersion,
                analysisTraceId,
                startedAt
        );
        boolean priorApprovedRecipientTransfer = selectedPolicy == 3
                && request.transaction().recipientAccountRef() != null
                && !transactionRepository.findPriorApprovedRecipientTransfers(
                    prepared.transaction().getExternalCustomerRef(),
                    prepared.transaction().getRecipientAccountRef(),
                    transactionId, request.evaluationCutoffAt(), PageRequest.of(0, 1))
                    .isEmpty();
        return new StartedRuleAnalysisV2Execution(started, request,
                priorApprovedRecipientTransfer, scenarioFacts, aggregationFacts);
    }

    private PreparedRuleAnalysisStart prepareStart(UUID transactionId, boolean cutoffSafeMl) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        FinancialTransaction transaction = transactionRepository
                .findByTransactionIdForUpdate(transactionId)
                .orElseThrow(TransactionNotFoundException::new);
        validateCanStart(transaction);
        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                cutoffSafeMl ? snapshotAssembler.assemble(transaction, true)
                        : snapshotAssembler.assemble(transaction);
        return new PreparedRuleAnalysisStart(transaction, snapshot);
    }

    private StartedRuleAnalysis persistStart(
            PreparedRuleAnalysisStart prepared,
            String scoringPolicyVersion,
            String featureVersion,
            String modelVersion,
            String analysisTraceId,
            Instant startedAt
    ) {
        FinancialTransaction transaction = prepared.transaction();
        RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot =
                prepared.snapshot();
        int selectedPolicy = RulePolicyVersion.from(snapshot.request().ruleVersions());
        scoringPolicyVersion = RulePolicyVersion.scoringPolicy(selectedPolicy);
        featureVersion = RulePolicyVersion.feature(selectedPolicy);
        if (modelVersion != null) scoringPolicyVersion = selectedPolicy == 5
                ? MlDetectionPolicy.SCN004_005_POLICY_VERSION
                : selectedPolicy == 4 ? MlDetectionPolicy.SCN001_002_POLICY_VERSION
                : selectedPolicy == 3 ? MlDetectionPolicy.SCN003_POLICY_VERSION
                : MlDetectionPolicy.POLICY_VERSION;

        int nextVersion = Math.addExact(
                detectionResultRepository.findMaximumVersionByTransactionPk(
                        transaction.getId()
                ),
                1
        );
        DetectionResult pending = DetectionResult.pending(
                transaction,
                nextVersion,
                snapshot.ruleSetVersion(),
                scoringPolicyVersion,
                featureVersion,
                modelVersion,
                snapshot.request().evaluationCutoffAt(),
                analysisTraceId
        );
        if (modelVersion != null) {
            pending.pinMl(selectedPolicy == 5
                            ? MlDetectionPolicy.SCN004_005_FEATURE_VERSION
                            : selectedPolicy == 4 ? MlDetectionPolicy.SCN001_002_FEATURE_VERSION
                            : MlDetectionPolicy.FEATURE_VERSION,
                    MlDetectionPolicy.shaForModel(modelVersion));
        }
        DetectionResult saved = detectionResultRepository.saveAndFlush(
                pending
        );

        saved.start(startedAt);
        transaction.startAnalysis();
        transactionRepository.saveAndFlush(transaction);

        StartedRuleAnalysis started = new StartedRuleAnalysis(
                transaction.getTransactionId(),
                saved.getDetectionResultId(),
                saved.getDetectionResultVersion(),
                saved.getRuleSetVersion(),
                saved.getScoringPolicyVersion(),
                saved.getFeatureVersion(),
                saved.getModelVersion(),
                saved.getEvaluationCutoffAt(),
                saved.getAnalysisTraceId()
        );
        return started;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAndAdopt(
            StartedRuleAnalysis started,
            int riskScore,
            RiskLevel riskLevel,
            Instant completedAt,
            List<RuleEvidenceDraft> evidenceDrafts
    ) {
        completeAndAdoptMl(started, riskScore, riskLevel, completedAt, evidenceDrafts, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAndAdoptMl(
            StartedRuleAnalysis started,
            int ruleRiskScore,
            RiskLevel ruleRiskLevel,
            Instant completedAt,
            List<RuleEvidenceDraft> evidenceDrafts,
            MlDetectionService.MlResult mlResult
    ) {
        completeAndAdoptMl(started, ruleRiskScore, ruleRiskLevel, completedAt,
                evidenceDrafts, mlResult, null, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAndAdoptMl(
            StartedRuleAnalysis started,
            int ruleRiskScore,
            RiskLevel ruleRiskLevel,
            Instant completedAt,
            List<RuleEvidenceDraft> evidenceDrafts,
            MlDetectionService.MlResult mlResult,
            ExternalRiskSnapshotRequest externalRisk,
            boolean priorApprovedRecipientTransfer
    ) {
        requireOpenGate();
        StartedRuleAnalysis validatedStarted = Objects.requireNonNull(
                started,
                "started must not be null"
        );
        FinancialTransaction transaction = transactionForUpdate(
                validatedStarted.transactionId()
        );
        DetectionResult result = resultForUpdate(
                validatedStarted.detectionResultId()
        );

        requireTransactionStatus(
                transaction,
                TransactionProcessingStatus.ANALYZING
        );
        requireResultStatus(result, DetectionAnalysisStatus.IN_PROGRESS);
        if (!result.belongsTo(transaction)) {
            throw new IllegalArgumentException(
                    "Detection result must belong to the locked transaction"
            );
        }
        requireStartedMatches(validatedStarted, transaction, result);
        if ((result.getModelVersion() != null) != (mlResult != null)) {
            throw new IllegalArgumentException("Pinned ML selection and result differ");
        }

        List<RuleEvidenceDraft> drafts = List.copyOf(
                Objects.requireNonNull(
                        evidenceDrafts,
                        "evidenceDrafts must not be null"
                )
        );
        List<DetectionEvidence> evidence = new java.util.ArrayList<>(drafts.stream()
                .map(draft -> RuleEvidenceAssembler.assemble(
                        result,
                        findRuleVersion(draft.ruleVersionId()),
                        draft
                ))
                .toList());
        if ("scoring-policy-v3".equals(result.getScoringPolicyVersion())
                || MlDetectionPolicy.SCN003_POLICY_VERSION.equals(result.getScoringPolicyVersion())) {
            if (externalRisk == null) {
                throw new IllegalArgumentException("SCN-003 requires validated External Risk");
            }
            boolean matched = externalRisk.matches().stream().anyMatch(match ->
                    match.subjectType() == ExternalRiskSubjectType.RECIPIENT_ACCOUNT
                    && match.riskType() == ExternalRiskType.SUSPICIOUS_ACCOUNT
                    && match.reasonCode() == ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT);
            List<DetectionEvidence> r005 = evidence.stream().filter(item ->
                    RuleV1ContractRegistry.EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT.equals(
                            item.getRuleCode())).toList();
            if (r005.size() != (matched ? 1 : 0)) {
                throw new IllegalArgumentException("R005 RULE and External Risk Evidence mismatch");
            }
            if (matched) {
                var summary = r005.get(0).getObservationSummary();
                if (!externalRisk.providerCode().equals(summary.path("providerCode").asText())
                        || !externalRisk.providerAsOf().equals(
                                Instant.parse(summary.path("providerAsOf").asText()))
                        || !externalRisk.lookedUpAt().equals(
                                Instant.parse(summary.path("lookedUpAt").asText()))) {
                    throw new IllegalArgumentException("R005 Evidence source and time mismatch");
                }
            }
            evidence.add(DetectionEvidence.externalRisk(result, externalRisk, evidence.size()));
            evidence.add(DetectionEvidence.recipientHistory(result,
                    priorApprovedRecipientTransfer, evidence.size()));
        }
        if (mlResult != null) {
            evidence.add(DetectionEvidence.ml(result, mlResult.reasonCode(),
                    mlResult.contribution(), mlResult.probabilityBasisPoints(),
                    result.getEvaluationCutoffAt(), evidence.size()));
        }
        evidenceRepository.saveAllAndFlush(evidence);

        if (mlResult == null) {
            result.complete(ruleRiskScore, ruleRiskLevel, completedAt);
        } else {
            int finalScore = MlDetectionPolicy.finalScore(ruleRiskScore, mlResult.contribution());
            result.completeMl(ruleRiskScore, mlResult.contribution(),
                    mlResult.probabilityBasisPoints(), MlDetectionPolicy.riskLevel(finalScore),
                    completedAt);
        }
        detectionResultRepository.saveAndFlush(result);

        transaction.adoptDetectionResult(result);
        transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAndAdoptV4(StartedRuleAnalysis started,
                                   RuleAnalysisRequestV3 request,
                                   RuleAnalysisResponse response,
                                   MlDetectionService.MlResult mlResult,
                                   Instant completedAt) {
        completeAndAdoptScenario(started, request, null, response, mlResult, completedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAndAdoptV5(StartedRuleAnalysis started,
                                   RuleAnalysisRequestV4 request,
                                   RuleAnalysisResponse response,
                                   MlDetectionService.MlResult mlResult,
                                   Instant completedAt) {
        completeAndAdoptScenario(started, request.base(), request, response, mlResult,
                completedAt);
    }

    private void completeAndAdoptScenario(StartedRuleAnalysis started,
                                   RuleAnalysisRequestV3 request,
                                   RuleAnalysisRequestV4 aggregationRequest,
                                   RuleAnalysisResponse response,
                                   MlDetectionService.MlResult mlResult,
                                   Instant completedAt) {
        requireOpenGate();
        Objects.requireNonNull(mlResult, "Scenario Rule requires pinned ML result");
        if (aggregationRequest == null) {
            ScenarioRuleResponseValidator.validate(request, response);
        } else {
            ScenarioAggregationRuleResponseValidator.validate(aggregationRequest, response);
        }
        FinancialTransaction transaction = transactionForUpdate(started.transactionId());
        DetectionResult result = resultForUpdate(started.detectionResultId());
        requireTransactionStatus(transaction, TransactionProcessingStatus.ANALYZING);
        requireResultStatus(result, DetectionAnalysisStatus.IN_PROGRESS);
        requireStartedMatches(started, transaction, result);
        if (!result.belongsTo(transaction)
                || !(aggregationRequest == null
                        ? MlDetectionPolicy.SCN001_002_POLICY_VERSION
                        : MlDetectionPolicy.SCN004_005_POLICY_VERSION)
                        .equals(result.getScoringPolicyVersion())
                || !(aggregationRequest == null
                        ? MlDetectionPolicy.SCN001_002_MODEL_VERSION
                        : MlDetectionPolicy.SCN004_005_MODEL_VERSION)
                        .equals(result.getModelVersion())
                || !(aggregationRequest == null
                        ? MlDetectionPolicy.SCN001_002_FEATURE_VERSION
                        : MlDetectionPolicy.SCN004_005_FEATURE_VERSION)
                        .equals(result.getMlFeatureVersion())
                || !(aggregationRequest == null
                        ? MlDetectionPolicy.SCN001_002_MODEL_SHA256
                        : MlDetectionPolicy.SCN004_005_MODEL_SHA256)
                        .equals(result.getModelSha256())) {
            throw new IllegalArgumentException("Scenario Rule pinned result mismatch");
        }
        var scoring = response.analysis().scoringResult();
        var groupSummary = JsonNodeFactory.instance.arrayNode();
        for (var group : scoring.groupSummaries()) {
            groupSummary.addObject().put("groupId", group.groupId().name())
                    .put("rawScore", group.rawScore()).put("cap", group.cap())
                    .put("appliedScore", group.appliedScore())
                    .put("reduction", group.reduction());
        }
        List<DetectionEvidence> evidence = new java.util.ArrayList<>();
        for (var item : response.analysis().evidence()) {
            RuleVersion version = findRuleVersion(item.ruleVersionId());
            if (!version.getFraudRule().getRuleCode().equals(item.ruleCode())
                    || version.getVersionNumber() != Integer.parseInt(item.ruleVersion())
                    || version.getWeight() != item.scoreContribution()) {
                throw new IllegalArgumentException("Rule v4 stored RuleVersion mismatch");
            }
            evidence.add(DetectionEvidence.ruleV4(result, version,
                    RuleV1ContractRegistry.displayDescriptionFor(item.reasonCode()),
                    item.observationSummary(), item.scoreContribution(),
                    item.appliedContribution(), item.evidenceOccurredAt(), evidence.size()));
        }
        evidence.add(DetectionEvidence.externalRiskV4(result, request.externalRisk(),
                request.scenarioFacts().exactProviderDeviceMatch(), evidence.size()));
        evidence.add(DetectionEvidence.scenarioHistory(result,
                request.scenarioFacts().baselineStatus(),
                request.scenarioFacts().baselineCount(),
                request.scenarioFacts().baselineDays(),
                request.scenarioFacts().recipientStatus(),
                request.scenarioFacts().deviceStatus(), evidence.size()));
        if (aggregationRequest != null) {
            evidence.add(DetectionEvidence.aggregationHistory(result,
                    aggregationRequest.aggregationFacts(), evidence.size()));
        }
        int ceiling = aggregationRequest == null ? 100
                : ScenarioAggregationRulePolicy.scoreCeiling(scoring);
        int appliedMl = Math.min(mlResult.contribution(),
                Math.max(0, ceiling - scoring.riskScore()));
        evidence.add(DetectionEvidence.mlV4(result, mlResult.reasonCode(),
                mlResult.contribution(), appliedMl, mlResult.probabilityBasisPoints(),
                result.getEvaluationCutoffAt(), evidence.size()));
        evidenceRepository.saveAllAndFlush(evidence);
        int finalScore = scoring.riskScore() + appliedMl;
        result.setScoreGroupSummary(groupSummary);
        result.completeMlV4(scoring.riskScore(), appliedMl,
                mlResult.probabilityBasisPoints(), MlDetectionPolicy.riskLevel(finalScore),
                completedAt);
        detectionResultRepository.saveAndFlush(result);
        transaction.adoptDetectionResult(result);
        transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failAnalysis(
            StartedRuleAnalysis started,
            String failureCode,
            Instant failedAt
    ) {
        requireOpenGate();
        StartedRuleAnalysis validatedStarted = Objects.requireNonNull(
                started,
                "started must not be null"
        );
        FinancialTransaction transaction = transactionForUpdate(
                validatedStarted.transactionId()
        );
        DetectionResult result = resultForUpdate(
                validatedStarted.detectionResultId()
        );

        if (!result.belongsTo(transaction)) {
            throw new IllegalArgumentException(
                    "Detection result must belong to the locked transaction"
            );
        }
        requireStartedMatches(validatedStarted, transaction, result);
        requireTransactionStatus(
                transaction,
                TransactionProcessingStatus.ANALYZING
        );
        requireResultStatus(result, DetectionAnalysisStatus.IN_PROGRESS);

        result.fail(failureCode, failedAt);
        transaction.failAnalysis();
        transactionRepository.saveAndFlush(transaction);
        recordFailedTerminalAfterCommit(transaction, failureCode);
    }

    private void requireOpenGate() {
        if (maintenanceGate != null) {
            maintenanceGate.requireOpen();
        }
    }

    private void recordFailedTerminalAfterCommit(
            FinancialTransaction transaction,
            String failureCode
    ) {
        Instant createdAt = transaction.getCreatedAt();
        Instant terminalAt = transaction.getUpdatedAt();
        Duration duration = null;
        if (createdAt != null && terminalAt != null) {
            Duration candidate = Duration.between(createdAt, terminalAt);
            if (!candidate.isNegative()) {
                duration = candidate;
            }
        }
        Duration recordedDuration = duration;
        afterCommit(() -> metricsRecorder.recordTransactionTerminal(
                TransactionProcessingStatus.FAILED,
                null,
                TransactionProcessingMetricsRecorder.FailureCategory
                        .fromRule(failureCode),
                recordedDuration
        ));
    }

    private void afterCommit(Runnable operation) {
        try {
            if (!TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                operation.run();
                            } catch (Throwable ignored) {
                                // Observability cannot change committed work.
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            // Synchronization registration cannot change business work.
        }
    }

    private FinancialTransaction transactionForUpdate(UUID transactionId) {
        return transactionRepository.findByTransactionIdForUpdate(
                transactionId
        ).orElseThrow(TransactionNotFoundException::new);
    }

    private DetectionResult resultForUpdate(UUID detectionResultId) {
        return detectionResultRepository.findByDetectionResultIdForUpdate(
                detectionResultId
        ).orElseThrow(
                () -> new IllegalArgumentException(
                        "Detection result does not exist"
                )
        );
    }

    private RuleVersion findRuleVersion(UUID ruleVersionId) {
        return ruleVersionRepository.findByRuleVersionId(ruleVersionId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Rule version does not exist"
                        )
                );
    }

    private void validateCanStart(FinancialTransaction transaction) {
        requireTransactionStatus(
                transaction,
                TransactionProcessingStatus.RECEIVED
        );
        if (transaction.getAdoptedDetectionResult() != null
                || transaction.getRiskLevel() != null
                || transaction.getRiskResponseOutcome() != null) {
            throw new IllegalStateException(
                    "Transaction must not have an analysis outcome"
            );
        }
    }

    private void requireTransactionStatus(
            FinancialTransaction transaction,
            TransactionProcessingStatus expected
    ) {
        if (transaction.getProcessingStatus() != expected) {
            throw new IllegalStateException(
                    "Transaction processing status must be " + expected
            );
        }
    }

    private void requireResultStatus(
            DetectionResult result,
            DetectionAnalysisStatus expected
    ) {
        if (result.getAnalysisStatus() != expected) {
            throw new IllegalStateException(
                    "Detection result status must be " + expected
            );
        }
    }

    private void requireStartedMatches(
            StartedRuleAnalysis started,
            FinancialTransaction transaction,
            DetectionResult result
    ) {
        boolean matches = started.transactionId().equals(
                transaction.getTransactionId()
        )
                && started.detectionResultId().equals(
                result.getDetectionResultId()
        )
                && started.detectionResultVersion()
                == result.getDetectionResultVersion()
                && started.ruleSetVersion().equals(result.getRuleSetVersion())
                && started.scoringPolicyVersion().equals(
                result.getScoringPolicyVersion()
        )
                && started.featureVersion().equals(result.getFeatureVersion())
                && Objects.equals(
                started.modelVersion(),
                result.getModelVersion()
        )
                && started.evaluationCutoffAt().equals(
                result.getEvaluationCutoffAt()
        )
                && started.analysisTraceId().equals(
                result.getAnalysisTraceId()
        );
        if (!matches) {
            throw new IllegalArgumentException(
                    "Started analysis does not match the stored result"
            );
        }
    }

    private record PreparedRuleAnalysisStart(
            FinancialTransaction transaction,
            RuleAnalysisSnapshotAssembler.AssembledRuleAnalysisSnapshot snapshot
    ) {
    }
}
