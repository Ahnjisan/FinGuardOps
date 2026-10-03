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
export interface AdoptedResult {
  readonly detectionResultId: string;
  readonly detectionResultVersion: number;
  readonly riskLevel: typeof RISK[number];
  readonly riskScore: number;
  readonly analysisCompletedAt: string;
  readonly ruleSetVersion: string;
  readonly scoringPolicyVersion: string;
  readonly ruleEvidence: readonly AdoptedRuleEvidence[];
}
export interface AdoptedDetectionResponse {
  readonly transactionId: string;
  readonly availability: Availability;
  readonly latestDetectionResultVersion: number | null;
  readonly latestAnalysisStatus: AnalysisStatus | null;
  readonly adoptedResult: AdoptedResult | null;
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
  return isObjectWithExactKeys(value, ["detectionResultId", "detectionResultVersion", "riskLevel", "riskScore",
    "analysisCompletedAt", "ruleSetVersion", "scoringPolicyVersion", "ruleEvidence"]) &&
    isUuidV4String(value.detectionResultId) && isVersion(value.detectionResultVersion) &&
    isEnumMember(value.riskLevel, RISK) && Number.isInteger(value.riskScore) &&
    (value.riskScore as number) >= 0 && (value.riskScore as number) <= 100 &&
    isUtcInstantString(value.analysisCompletedAt) &&
    typeof value.ruleSetVersion === "string" && value.ruleSetVersion.length > 0 && value.ruleSetVersion.length <= 64 &&
    typeof value.scoringPolicyVersion === "string" && value.scoringPolicyVersion.length > 0 &&
    value.scoringPolicyVersion.length <= 64 && isArrayOf(value.ruleEvidence, isRule);
}
export function isAdoptedDetectionResponse(value: unknown): value is AdoptedDetectionResponse {
  if (!isObjectWithExactKeys(value, ["transactionId", "availability", "latestDetectionResultVersion",
    "latestAnalysisStatus", "adoptedResult"]) || !isUuidV4String(value.transactionId) ||
    !isEnumMember(value.availability, AVAILABILITY)) return false;
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
