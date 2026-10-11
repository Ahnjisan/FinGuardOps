import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { isArrayOf, isEnumMember, isJsonObject, isObjectWithExactKeys, isUtcInstantString, isUuidV4String } from "./responseValidation";

const ANALYSIS = ["PENDING", "IN_PROGRESS", "COMPLETED", "FAILED"] as const;
const AVAILABILITY = ["NO_HISTORY", "PENDING", "IN_PROGRESS", "FAILED", "COMPLETED_NOT_ADOPTED", "AVAILABLE"] as const;
const RISK = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
export type AnalysisStatus = typeof ANALYSIS[number];
export type Availability = typeof AVAILABILITY[number];

export interface AdoptedRuleEvidence {
  readonly ruleCode: string;
  readonly ruleVersion: string;
  readonly reasonCode: string;
  readonly scoreContribution: number;
  readonly appliedContribution?: number;
}
export interface AdoptedMlEvidence {
  readonly reasonCode: string;
  readonly scoreContribution: number;
  readonly probabilityBasisPoints: number;
}
export interface Scn003Evidence {
  readonly sourceVersion: "SCN003-contract-v1";
  readonly providerCode: string;
  readonly providerAsOf: string;
  readonly lookedUpAt: string;
  readonly recipientAccountMatched: boolean;
  readonly priorApprovedRecipientTransferObserved: boolean;
}
export interface AdoptedResult {
  readonly detectionResultId: string;
  readonly detectionResultVersion: number;
  readonly riskLevel: typeof RISK[number];
  readonly riskScore: number;
  readonly analysisCompletedAt: string;
  readonly ruleSetVersion: string;
  readonly scoringPolicyVersion: string;
  readonly ruleEvidence: readonly AdoptedRuleEvidence[];
  readonly ruleScore?: number;
  readonly mlContribution?: number | null;
  readonly mlStatus?: "APPLIED" | "RULE_ONLY";
  readonly modelVersion?: string | null;
  readonly mlFeatureVersion?: string | null;
  readonly modelSha256?: string | null;
  readonly mlEvidence?: readonly AdoptedMlEvidence[];
  readonly scn003Evidence?: Scn003Evidence;
  readonly scoreGroupSummary?: readonly ScoreGroupSummary[];
  readonly mlRawContribution?: number;
  readonly scn001002Evidence?: Scn001002Evidence;
  readonly aggregationEvidence?: AggregationEvidence;
  readonly fundFlowEvidence?: FundFlowEvidence;
  readonly openBankingEvidence?: OpenBankingEvidence;
}
export interface ScoreGroupSummary {
  readonly groupId: "amount" | "security" | "beneficiary" | "external_recipient" | "flow_aggregation" | "flow_link" | "open_banking_chain";
  readonly rawScore: number;
  readonly cap: number;
  readonly appliedScore: number;
  readonly reduction: number;
}
export interface Scn001002Evidence {
  readonly sourceVersion: "SCN001-002-contract-v1";
  readonly baselineStatus: string;
  readonly baselineCount: number;
  readonly baselineDays: number;
  readonly recipientStatus: string;
  readonly deviceStatus: string;
  readonly providerCode: string;
  readonly providerAsOf: string;
  readonly lookedUpAt: string;
  readonly recipientAccountMatched: boolean;
  readonly exactDeviceMatched: boolean;
}
export interface AggregationWindow {
  readonly windowStartAt: string;
  readonly status: "AVAILABLE" | "UNKNOWN";
  readonly transferCount: number;
  readonly distinctCount: number;
  readonly totalAmount: string;
  readonly unknownCount: number;
  readonly coverageFactId?: string;
}
export interface AggregationEvidence {
  readonly sourceVersion: "SCN004-005-contract-v1";
  readonly mockSourceCode: "LOCAL_FDS_MOCK";
  readonly mockSourceVersion: "v1";
  readonly evaluationCutoffAt: string;
  readonly outbound: AggregationWindow;
  readonly inbound: AggregationWindow;
}
export interface FundFlowStep {
  readonly status: "AVAILABLE" | "UNKNOWN";
  readonly reasonCode: string;
  readonly sourceAmount: string;
  readonly linkedAmount: string;
  readonly ratioBasisPoints: number;
  readonly linkedPriorTransactionIds: readonly string[];
  readonly sourceTransactionId?: string;
  readonly currentLinkId?: string;
  readonly historyCoverageFactId?: string;
  readonly sourceOccurredAt?: string;
  readonly sourceApprovedAt?: string;
  readonly beneficiaryEventId?: string;
}
export interface FundFlowEvidence {
  readonly sourceVersion: "SCN006-007-contract-v1";
  readonly mockSourceCode: "LOCAL_FDS_MOCK";
  readonly mockSourceVersion: "v2";
  readonly evaluationCutoffAt: string;
  readonly atm: FundFlowStep;
  readonly loan: FundFlowStep;
}
export interface OpenBankingStage {
  readonly sourceTransactionId: string;
  readonly ownershipAssertionId: string;
  readonly linkId: string;
  readonly occurredAt: string;
  readonly approvedAt: string;
  readonly amount: string;
}
export interface OpenBankingEvidence {
  readonly sourceVersion: "SCN008-contract-v1";
  readonly mockSourceCode: "LOCAL_OPEN_BANKING_MOCK";
  readonly mockSourceVersion: "v1";
  readonly evaluationCutoffAt: string;
  readonly status: "AVAILABLE" | "UNKNOWN";
  readonly reasonCode: string;
  readonly concentrationAmount: string;
  readonly resendAmount: string;
  readonly ratioBasisPoints: number;
  readonly historyCoverageFactId?: string;
  readonly concentrationStartedAt?: string;
  readonly concentrationCompletedAt?: string;
  readonly stages: readonly OpenBankingStage[];
}
export interface AdoptedDetectionResponse {
  readonly transactionId: string;
  readonly availability: Availability;
  readonly latestDetectionResultVersion: number | null;
  readonly latestAnalysisStatus: AnalysisStatus | null;
  readonly adoptedResult: AdoptedResult | null;
  readonly latestFailureCode?: string | null;
}

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 && value <= 2147483647;
}
function isRule(value: unknown): value is AdoptedRuleEvidence {
  return (isObjectWithExactKeys(value, ["ruleCode", "ruleVersion", "reasonCode", "scoreContribution"]) ||
    isObjectWithExactKeys(value, ["ruleCode", "ruleVersion", "reasonCode", "scoreContribution", "appliedContribution"])) &&
    typeof value.ruleCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.ruleCode) &&
    typeof value.reasonCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.reasonCode) &&
    typeof value.ruleVersion === "string" && /^[0-9]+$/.test(value.ruleVersion) &&
    Number.isInteger(value.scoreContribution) && (value.scoreContribution as number) >= 0 &&
    (value.scoreContribution as number) <= 100 &&
    (value.appliedContribution === undefined ||
      (Number.isInteger(value.appliedContribution) && (value.appliedContribution as number) >= 0 &&
       (value.appliedContribution as number) <= (value.scoreContribution as number)));
}
function isScoreGroup(value: unknown): value is ScoreGroupSummary {
  return isObjectWithExactKeys(value, ["groupId", "rawScore", "cap", "appliedScore", "reduction"]) &&
    ["amount", "security", "beneficiary", "external_recipient", "flow_aggregation", "flow_link", "open_banking_chain"].includes(String(value.groupId)) &&
    [value.rawScore, value.cap, value.appliedScore, value.reduction].every(item =>
      Number.isInteger(item) && (item as number) >= 0 && (item as number) <= 200) &&
    (value.rawScore as number) - (value.appliedScore as number) === value.reduction &&
    (value.appliedScore as number) <= (value.cap as number);
}
function isScenario(value: unknown): value is Scn001002Evidence {
  return isObjectWithExactKeys(value, ["sourceVersion", "baselineStatus", "baselineCount", "baselineDays",
    "recipientStatus", "deviceStatus", "providerCode", "providerAsOf", "lookedUpAt",
    "recipientAccountMatched", "exactDeviceMatched"]) &&
    value.sourceVersion === "SCN001-002-contract-v1" &&
    [value.baselineStatus, value.recipientStatus, value.deviceStatus, value.providerCode].every(item =>
      typeof item === "string" && item.length > 0 && item.length <= 64) &&
    Number.isSafeInteger(value.baselineCount) && (value.baselineCount as number) >= 0 &&
    Number.isSafeInteger(value.baselineDays) && (value.baselineDays as number) >= 0 &&
    isUtcInstantString(value.providerAsOf) && isUtcInstantString(value.lookedUpAt) &&
    typeof value.recipientAccountMatched === "boolean" && typeof value.exactDeviceMatched === "boolean";
}
function hasV4Keys(value: unknown, keys: readonly string[]): boolean {
  return isObjectWithExactKeys(value, keys);
}
function isAggregationWindow(value: unknown): value is AggregationWindow {
  if (!isObjectWithExactKeys(value, ["windowStartAt", "status", "transferCount", "distinctCount", "totalAmount", "unknownCount"]) &&
      !isObjectWithExactKeys(value, ["windowStartAt", "status", "transferCount", "distinctCount", "totalAmount", "unknownCount", "coverageFactId"])) return false;
  return ["AVAILABLE", "UNKNOWN"].includes(String(value.status)) &&
    isUtcInstantString(value.windowStartAt) &&
    [value.transferCount, value.distinctCount, value.unknownCount].every(item =>
      Number.isSafeInteger(item) && (item as number) >= 0) &&
    (value.distinctCount as number) <= (value.transferCount as number) &&
    typeof value.totalAmount === "string" && /^(0|[1-9][0-9]*)$/.test(value.totalAmount) &&
    (value.coverageFactId === undefined || isUuidV4String(value.coverageFactId)) &&
    (value.status !== "AVAILABLE" ||
      (value.coverageFactId !== undefined && value.unknownCount === 0));
}
function isAggregation(value: unknown): value is AggregationEvidence {
  return isObjectWithExactKeys(value, ["sourceVersion", "mockSourceCode", "mockSourceVersion", "evaluationCutoffAt", "outbound", "inbound"]) &&
    value.sourceVersion === "SCN004-005-contract-v1" &&
    value.mockSourceCode === "LOCAL_FDS_MOCK" && value.mockSourceVersion === "v1" &&
    isUtcInstantString(value.evaluationCutoffAt) &&
    isAggregationWindow(value.outbound) && isAggregationWindow(value.inbound);
}
function isFundStep(value: unknown, loan: boolean): value is FundFlowStep {
  if (!isJsonObject(value)) return false;
  const basic = ["status", "reasonCode", "sourceAmount", "linkedAmount",
    "ratioBasisPoints", "linkedPriorTransactionIds"];
  const proven = [...basic, "sourceTransactionId", "currentLinkId", "historyCoverageFactId",
    "sourceOccurredAt", "sourceApprovedAt", ...(loan ? ["beneficiaryEventId"] : [])];
  if (value.status === "UNKNOWN") {
    return isObjectWithExactKeys(value, basic) &&
      typeof value.reasonCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.reasonCode) &&
      value.sourceAmount === "0" && value.linkedAmount === "0" &&
      value.ratioBasisPoints === 0 && Array.isArray(value.linkedPriorTransactionIds) &&
      value.linkedPriorTransactionIds.length === 0;
  }
  return value.status === "AVAILABLE" && isObjectWithExactKeys(value, proven) &&
    typeof value.reasonCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.reasonCode) &&
    isUuidV4String(value.sourceTransactionId) && isUuidV4String(value.currentLinkId) &&
    isUuidV4String(value.historyCoverageFactId) &&
    (!loan || isUuidV4String(value.beneficiaryEventId)) &&
    isUtcInstantString(value.sourceOccurredAt) && isUtcInstantString(value.sourceApprovedAt) &&
    typeof value.sourceAmount === "string" && /^[1-9][0-9]*$/.test(value.sourceAmount) &&
    typeof value.linkedAmount === "string" && /^[1-9][0-9]*$/.test(value.linkedAmount) &&
    Number.isSafeInteger(value.ratioBasisPoints) && (value.ratioBasisPoints as number) >= 0 &&
    (value.ratioBasisPoints as number) <= 10000 &&
    isArrayOf(value.linkedPriorTransactionIds, isUuidV4String);
}
function isFundFlow(value: unknown): value is FundFlowEvidence {
  return isObjectWithExactKeys(value, ["sourceVersion", "mockSourceCode", "mockSourceVersion",
    "evaluationCutoffAt", "atm", "loan"]) &&
    value.sourceVersion === "SCN006-007-contract-v1" &&
    value.mockSourceCode === "LOCAL_FDS_MOCK" && value.mockSourceVersion === "v2" &&
    isUtcInstantString(value.evaluationCutoffAt) &&
    isFundStep(value.atm, false) && isFundStep(value.loan, true);
}
function isOpenStage(value: unknown): value is OpenBankingStage {
  return isObjectWithExactKeys(value, ["sourceTransactionId", "ownershipAssertionId", "linkId",
    "occurredAt", "approvedAt", "amount"]) &&
    isUuidV4String(value.sourceTransactionId) && isUuidV4String(value.ownershipAssertionId) &&
    isUuidV4String(value.linkId) && isUtcInstantString(value.occurredAt) &&
    isUtcInstantString(value.approvedAt) &&
    typeof value.amount === "string" && /^[1-9][0-9]*$/.test(value.amount);
}
function isOpenBanking(value: unknown): value is OpenBankingEvidence {
  if (!isJsonObject(value)) return false;
  const base = ["sourceVersion", "mockSourceCode", "mockSourceVersion", "evaluationCutoffAt",
    "status", "reasonCode", "concentrationAmount", "resendAmount", "ratioBasisPoints", "stages"];
  const proven = [...base, "historyCoverageFactId", "concentrationStartedAt", "concentrationCompletedAt"];
  if (!isObjectWithExactKeys(value, value.status === "AVAILABLE" ? proven : base) ||
      value.sourceVersion !== "SCN008-contract-v1" || value.mockSourceCode !== "LOCAL_OPEN_BANKING_MOCK" ||
      value.mockSourceVersion !== "v1" || !isUtcInstantString(value.evaluationCutoffAt) ||
      typeof value.reasonCode !== "string" || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.reasonCode) ||
      typeof value.concentrationAmount !== "string" || !/^(0|[1-9][0-9]*)$/.test(value.concentrationAmount) ||
      typeof value.resendAmount !== "string" || !/^(0|[1-9][0-9]*)$/.test(value.resendAmount) ||
      !Number.isSafeInteger(value.ratioBasisPoints) || (value.ratioBasisPoints as number) < 0 ||
      (value.ratioBasisPoints as number) > 10000 || !isArrayOf(value.stages, isOpenStage)) return false;
  if (value.status === "UNKNOWN") return value.stages.length === 0 &&
    value.concentrationAmount === "0" && value.resendAmount === "0" && value.ratioBasisPoints === 0;
  return value.status === "AVAILABLE" && isUuidV4String(value.historyCoverageFactId) &&
    isUtcInstantString(value.concentrationStartedAt) && isUtcInstantString(value.concentrationCompletedAt) &&
    value.stages.length >= 2 && new Set(value.stages.map(stage => stage.ownershipAssertionId)).size >= 2;
}
function isAdopted(value: unknown): value is AdoptedResult {
  if (!isJsonObject(value)) return false;
  const oldKeys = ["detectionResultId", "detectionResultVersion", "riskLevel", "riskScore",
    "analysisCompletedAt", "ruleSetVersion", "scoringPolicyVersion", "ruleEvidence"];
  const newKeys = [...oldKeys, "ruleScore", "mlContribution", "mlStatus", "modelVersion",
    "mlFeatureVersion", "modelSha256", "mlEvidence"];
  const scn003Keys = [...newKeys, "scn003Evidence"];
  const scn003 = isObjectWithExactKeys(value, scn003Keys);
  const v4 = hasV4Keys(value, [...newKeys, "scoreGroupSummary", "mlRawContribution", "scn001002Evidence"]);
  const v5 = hasV4Keys(value, [...newKeys, "scoreGroupSummary", "mlRawContribution", "scn001002Evidence", "aggregationEvidence"]);
  const v6 = hasV4Keys(value, [...newKeys, "scoreGroupSummary", "mlRawContribution", "scn001002Evidence", "aggregationEvidence", "fundFlowEvidence"]);
  const v7 = hasV4Keys(value, [...newKeys, "scoreGroupSummary", "mlRawContribution", "scn001002Evidence", "aggregationEvidence", "fundFlowEvidence", "openBankingEvidence"]);
  if (!(isObjectWithExactKeys(value, oldKeys) || isObjectWithExactKeys(value, newKeys) || scn003 || v4 || v5 || v6 || v7)) return false;
  if (v4 || v5 || v6 || v7) {
    if (value.scoringPolicyVersion !== (v7 ? "rule-ml-policy-v6" : v6 ? "rule-ml-policy-v5" : v5 ? "rule-ml-policy-v4" : "rule-ml-policy-v3") ||
      value.mlFeatureVersion !== (v7 ? "fraud-feature-v5" : v6 ? "fraud-feature-v4" : v5 ? "fraud-feature-v3" : "fraud-feature-v2") ||
      value.modelVersion !== (v7 ? "fraud-logistic-v6" : v6 ? "fraud-logistic-v5" : v5 ? "fraud-logistic-v4" : "fraud-logistic-v3") ||
      ((v5 || v6 || v7) && !isAggregation(value.aggregationEvidence)) ||
      ((v6 || v7) && !isFundFlow(value.fundFlowEvidence)) ||
      (v7 && !isOpenBanking(value.openBankingEvidence)) ||
      !isScenario(value.scn001002Evidence) || !isArrayOf(value.scoreGroupSummary, isScoreGroup) ||
      value.scoreGroupSummary.length !== (v7 ? 7 : v6 ? 6 : v5 ? 5 : 4) || !isArrayOf(value.ruleEvidence, isRule) ||
      value.ruleEvidence.some(item => item.appliedContribution === undefined) ||
      !Number.isInteger(value.ruleScore) || !Number.isInteger(value.mlContribution) ||
      !Number.isInteger(value.mlRawContribution) ||
      value.mlStatus !== "APPLIED" ||
      (value.mlRawContribution as number) < (value.mlContribution as number) ||
      (value.mlRawContribution as number) > 40 ||
      value.ruleEvidence.reduce((sum, item) => sum + (item.appliedContribution ?? 0), 0) !== value.ruleScore ||
      value.scoreGroupSummary.reduce((sum, item) => sum + item.appliedScore, 0) !== value.ruleScore ||
      (value.ruleScore as number) + (value.mlContribution as number) !== value.riskScore ||
      !Array.isArray(value.mlEvidence) || value.mlEvidence.length !== 1 ||
      !isObjectWithExactKeys(value.mlEvidence[0], ["reasonCode", "scoreContribution", "probabilityBasisPoints"]) ||
      value.mlEvidence[0].scoreContribution !== value.mlRawContribution ||
      !Number.isInteger(value.mlEvidence[0].probabilityBasisPoints) ||
      typeof value.modelVersion !== "string" ||
      typeof value.modelSha256 !== "string" || !/^[0-9a-f]{64}$/.test(value.modelSha256)) return false;
    if (v6 || v7) {
      const flow = value.fundFlowEvidence;
      if (!isFundFlow(flow)) return false;
      const rules = value.ruleEvidence as AdoptedRuleEvidence[];
      if (rules.some(item => item.ruleCode === "VERIFIED_INBOUND_ATM_DRAIN") !==
            (flow.atm.status === "AVAILABLE" && flow.atm.ratioBasisPoints >= 8000) ||
          rules.some(item => item.ruleCode === "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN") !==
            (flow.loan.status === "AVAILABLE" && flow.loan.ratioBasisPoints >= 8000)) return false;
    }
    if (v7) {
      const open = value.openBankingEvidence;
      if (!isOpenBanking(open) || value.ruleEvidence.some(item =>
        item.ruleCode === "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND") !==
        (open.status === "AVAILABLE" && open.stages.length >= 2 &&
          Number(open.concentrationAmount) >= 5_000_000 && open.ratioBasisPoints >= 8000)) return false;
    }
    const level = value.riskScore < 20 ? "LOW" : value.riskScore < 50 ? "MEDIUM" :
      value.riskScore < 80 ? "HIGH" : "CRITICAL";
    return value.riskLevel === level && isUuidV4String(value.detectionResultId) &&
      isVersion(value.detectionResultVersion) && isUtcInstantString(value.analysisCompletedAt) &&
      typeof value.ruleSetVersion === "string" && value.ruleSetVersion.length > 0;
  }
  const scn003Policy = value.scoringPolicyVersion === "scoring-policy-v3" ||
    value.scoringPolicyVersion === "rule-ml-policy-v2";
  if (scn003Policy !== scn003) return false;
  if (scn003) {
    const item = value.scn003Evidence;
    if (!isObjectWithExactKeys(item, ["sourceVersion", "providerCode", "providerAsOf", "lookedUpAt",
      "recipientAccountMatched", "priorApprovedRecipientTransferObserved"]) ||
      item.sourceVersion !== "SCN003-contract-v1" ||
      typeof item.providerCode !== "string" || !/^[A-Z][A-Z0-9_]{0,63}$/.test(item.providerCode) ||
      !isUtcInstantString(item.providerAsOf) || !isUtcInstantString(item.lookedUpAt) ||
      typeof item.recipientAccountMatched !== "boolean" ||
      typeof item.priorApprovedRecipientTransferObserved !== "boolean") return false;
    if (!isArrayOf(value.ruleEvidence, isRule)) return false;
    const hasR005 = value.ruleEvidence.some(rule =>
      rule.ruleCode === "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT" && rule.ruleVersion === "1" &&
      rule.scoreContribution === 40);
    if (hasR005 !== item.recipientAccountMatched) return false;
  }
  if (isObjectWithExactKeys(value, oldKeys) &&
      value.scoringPolicyVersion === "rule-ml-policy-v1") return false;
  if (isObjectWithExactKeys(value, newKeys) || scn003) {
    if (!Number.isInteger(value.ruleScore) || (value.ruleScore as number) < 0 ||
        (value.ruleScore as number) > 100 || !Array.isArray(value.mlEvidence)) return false;
    if (value.mlStatus === "APPLIED") {
      if (!Number.isInteger(value.mlContribution) || (value.mlContribution as number) < 0 ||
          (value.mlContribution as number) > 40 || value.mlEvidence.length !== 1 ||
          typeof value.modelVersion !== "string" || value.modelVersion.length === 0 ||
          value.mlFeatureVersion !== "fraud-feature-v1" ||
          typeof value.modelSha256 !== "string" || !/^[0-9a-f]{64}$/.test(value.modelSha256) ||
          value.riskScore !== Math.min(100, (value.ruleScore as number) + (value.mlContribution as number))) return false;
      const finalLevel = value.riskScore < 20 ? "LOW" : value.riskScore < 50 ? "MEDIUM" :
        value.riskScore < 80 ? "HIGH" : "CRITICAL";
      if (value.riskLevel !== finalLevel) return false;
      const item: unknown = value.mlEvidence[0];
      if (!isObjectWithExactKeys(item, ["reasonCode", "scoreContribution", "probabilityBasisPoints"]) ||
          !["ML_RISK_SIGNAL", "ML_BELOW_THRESHOLD"].includes(String(item.reasonCode)) ||
          item.scoreContribution !== value.mlContribution ||
          !Number.isInteger(item.probabilityBasisPoints) ||
          (item.probabilityBasisPoints as number) < 0 ||
          (item.probabilityBasisPoints as number) > 10000 ||
          item.reasonCode !== ((item.probabilityBasisPoints as number) > 5000 ?
            "ML_RISK_SIGNAL" : "ML_BELOW_THRESHOLD") ||
          value.mlContribution !== ((item.probabilityBasisPoints as number) <= 5000 ? 0 :
            Math.floor((((item.probabilityBasisPoints as number) - 5000) * 40 + 2500) / 5000)) ||
          !["rule-ml-policy-v1", "rule-ml-policy-v2"].includes(String(value.scoringPolicyVersion))) return false;
    } else if (value.mlStatus !== "RULE_ONLY" ||
        ["rule-ml-policy-v1", "rule-ml-policy-v2"].includes(String(value.scoringPolicyVersion)) || value.mlContribution !== null ||
        value.modelVersion !== null || value.mlFeatureVersion !== null ||
        value.modelSha256 !== null || value.mlEvidence.length !== 0 ||
        value.ruleScore !== value.riskScore) return false;
  }
  return (
    isUuidV4String(value.detectionResultId) && isVersion(value.detectionResultVersion) &&
    isEnumMember(value.riskLevel, RISK) && Number.isInteger(value.riskScore) &&
    (value.riskScore as number) >= 0 && (value.riskScore as number) <= 100 &&
    isUtcInstantString(value.analysisCompletedAt) &&
    typeof value.ruleSetVersion === "string" && value.ruleSetVersion.length > 0 && value.ruleSetVersion.length <= 64 &&
    typeof value.scoringPolicyVersion === "string" && value.scoringPolicyVersion.length > 0 &&
    value.scoringPolicyVersion.length <= 64 && isArrayOf(value.ruleEvidence, isRule));
}
export function isAdoptedDetectionResponse(value: unknown): value is AdoptedDetectionResponse {
  const oldKeys = ["transactionId", "availability", "latestDetectionResultVersion",
    "latestAnalysisStatus", "adoptedResult"];
  const newKeys = [...oldKeys, "latestFailureCode"];
  if (!(isObjectWithExactKeys(value, oldKeys) || isObjectWithExactKeys(value, newKeys)) ||
    !isUuidV4String(value.transactionId) ||
    !isEnumMember(value.availability, AVAILABILITY)) return false;
  if (isObjectWithExactKeys(value, newKeys) && value.latestFailureCode !== null &&
      (typeof value.latestFailureCode !== "string" || !/^ML_[A-Z_]{1,60}$/.test(value.latestFailureCode) ||
       value.latestAnalysisStatus !== "FAILED")) return false;
  if (value.availability === "NO_HISTORY") {
    return value.latestDetectionResultVersion === null && value.latestAnalysisStatus === null &&
      value.adoptedResult === null;
  }
  if (!isVersion(value.latestDetectionResultVersion) || !isEnumMember(value.latestAnalysisStatus, ANALYSIS)) return false;
  if (value.availability === "AVAILABLE") {
    return isAdopted(value.adoptedResult) &&
      value.adoptedResult.detectionResultVersion <= value.latestDetectionResultVersion;
  }
  const expected = value.availability === "COMPLETED_NOT_ADOPTED" ? "COMPLETED" : value.availability;
  return value.adoptedResult === null && value.latestAnalysisStatus === expected;
}

export async function fetchAdoptedDetection(
  authClient: CredentialAuthClient, transactionId: string, signal?: AbortSignal,
): Promise<AdoptedDetectionResponse> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "adopted-detection-result", params: { transactionId }, expectedStatus: 200,
    validate: (body): body is AdoptedDetectionResponse =>
      isAdoptedDetectionResponse(body) && body.transactionId === transactionId,
    signal,
  });
  return result.data;
}
