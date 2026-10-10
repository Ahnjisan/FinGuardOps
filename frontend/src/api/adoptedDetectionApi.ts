import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { isArrayOf, isEnumMember, isObjectWithExactKeys, isUtcInstantString, isUuidV4String } from "./responseValidation";

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
  return isObjectWithExactKeys(value, ["ruleCode", "ruleVersion", "reasonCode", "scoreContribution"]) &&
    typeof value.ruleCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.ruleCode) &&
    typeof value.reasonCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value.reasonCode) &&
    typeof value.ruleVersion === "string" && /^[0-9]+$/.test(value.ruleVersion) &&
    Number.isInteger(value.scoreContribution) && (value.scoreContribution as number) >= 0 &&
    (value.scoreContribution as number) <= 100;
}
function isAdopted(value: unknown): value is AdoptedResult {
  const oldKeys = ["detectionResultId", "detectionResultVersion", "riskLevel", "riskScore",
    "analysisCompletedAt", "ruleSetVersion", "scoringPolicyVersion", "ruleEvidence"];
  const newKeys = [...oldKeys, "ruleScore", "mlContribution", "mlStatus", "modelVersion",
    "mlFeatureVersion", "modelSha256", "mlEvidence"];
  const scn003Keys = [...newKeys, "scn003Evidence"];
  const scn003 = isObjectWithExactKeys(value, scn003Keys);
  if (!(isObjectWithExactKeys(value, oldKeys) || isObjectWithExactKeys(value, newKeys) || scn003)) return false;
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
