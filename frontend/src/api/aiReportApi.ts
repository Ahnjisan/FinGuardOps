import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import { InvalidResponseError, RequestNotAllowedError } from "./errors";

const STATUSES = ["PENDING", "GENERATING", "COMPLETED", "FALLBACK_COMPLETED", "FAILED"];
const SOURCES = ["LLM", "TEMPLATE_FALLBACK"];

export interface AiReportRequestStatus {
  readonly aiRequestId: string;
  readonly executionId: string | null;
  readonly executionShared: boolean;
  readonly initiatingAiRequestId: string | null;
  readonly reportId: string | null;
  readonly caseId: string;
  readonly detectionResultVersion: number;
  readonly reportStatus: string;
  readonly reportSource: string | null;
  readonly sourceAiRequestId: string | null;
  readonly cacheHit: boolean;
  readonly requestedAt: string;
  readonly generatedAt: string | null;
  readonly failureCode: string | null;
  readonly fallbackTriggerCode: string | null;
  readonly resultLocation: string;
  readonly traceId: string;
}

export interface AiReportBody {
  readonly reportId: string;
  readonly executionId: string;
  readonly initiatingAiRequestId: string;
  readonly caseId: string;
  readonly detectionResultVersion: number;
  readonly reportStatus: string;
  readonly reportSource: string;
  readonly summary: string;
  readonly keyReasons: readonly { readonly reasonCode: string; readonly description: string }[];
  readonly timelineSummary: string;
  readonly investigationChecklist: readonly string[];
  readonly promptVersion: string;
  readonly modelVersion: string;
  readonly generatedAt: string;
  readonly failureCode: string | null;
  readonly fallbackTriggerCode: string | null;
  readonly traceId: string;
}

export interface AiReportCurrent {
  readonly caseId: string;
  readonly currentReport: AiReportBody | null;
  readonly latestRequest: AiReportRequestStatus | null;
  readonly traceId: string;
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function onlyKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key)) &&
    keys.every((key) => Object.hasOwn(value, key));
}

function version(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 1;
}

function uuid(value: unknown): value is string {
  return typeof value === "string" && isCanonicalUuidV4(value);
}

function status(value: unknown): value is AiReportRequestStatus {
  if (!object(value)) return false;
  return onlyKeys(value, ["aiRequestId", "executionId", "executionShared",
    "initiatingAiRequestId", "reportId", "caseId", "detectionResultVersion",
    "reportStatus", "reportSource", "sourceAiRequestId", "cacheHit", "requestedAt",
    "generatedAt", "failureCode", "fallbackTriggerCode", "resultLocation", "traceId"]) &&
    uuid(value.aiRequestId) &&
    (value.executionId === null || uuid(value.executionId)) &&
    typeof value.executionShared === "boolean" &&
    (value.initiatingAiRequestId === null || uuid(value.initiatingAiRequestId)) &&
    (value.reportId === null || uuid(value.reportId)) &&
    uuid(value.caseId) &&
    version(value.detectionResultVersion) && STATUSES.includes(String(value.reportStatus)) &&
    (value.reportSource === null || SOURCES.includes(String(value.reportSource))) &&
    (value.sourceAiRequestId === null || uuid(value.sourceAiRequestId)) &&
    typeof value.cacheHit === "boolean" && typeof value.requestedAt === "string" &&
    (value.generatedAt === null || typeof value.generatedAt === "string") &&
    (value.failureCode === null || typeof value.failureCode === "string") &&
    (value.fallbackTriggerCode === null || typeof value.fallbackTriggerCode === "string") &&
    value.resultLocation === `/api/v1/cases/${value.caseId}/ai-reports/current` &&
    typeof value.traceId === "string";
}

function report(value: unknown): value is AiReportBody {
  if (!object(value)) return false;
  return onlyKeys(value, ["reportId", "executionId", "initiatingAiRequestId", "caseId",
    "detectionResultVersion", "reportStatus", "reportSource", "summary", "keyReasons",
    "timelineSummary", "investigationChecklist", "promptVersion", "modelVersion",
    "generatedAt", "failureCode", "fallbackTriggerCode", "traceId"]) &&
    uuid(value.reportId) && uuid(value.executionId) &&
    uuid(value.initiatingAiRequestId) && uuid(value.caseId) &&
    version(value.detectionResultVersion) &&
    ["COMPLETED", "FALLBACK_COMPLETED"].includes(String(value.reportStatus)) &&
    SOURCES.includes(String(value.reportSource)) && typeof value.summary === "string" &&
    Array.isArray(value.keyReasons) && value.keyReasons.every((reason) =>
      object(reason) && onlyKeys(reason, ["reasonCode", "description"]) &&
      typeof reason.reasonCode === "string" && typeof reason.description === "string") &&
    typeof value.timelineSummary === "string" &&
    Array.isArray(value.investigationChecklist) &&
    value.investigationChecklist.every((item) => typeof item === "string") &&
    typeof value.promptVersion === "string" && typeof value.modelVersion === "string" &&
    typeof value.generatedAt === "string" &&
    (value.failureCode === null || typeof value.failureCode === "string") &&
    (value.fallbackTriggerCode === null || typeof value.fallbackTriggerCode === "string") &&
    typeof value.traceId === "string";
}

function current(value: unknown): value is AiReportCurrent {
  return object(value) && onlyKeys(value, ["caseId", "currentReport", "latestRequest", "traceId"]) &&
    uuid(value.caseId) &&
    (value.currentReport === null || report(value.currentReport)) &&
    (value.latestRequest === null || status(value.latestRequest)) &&
    typeof value.traceId === "string";
}

export async function fetchAiReportCurrent(auth: CredentialAuthClient, caseId: string,
  signal?: AbortSignal): Promise<AiReportCurrent> {
  const result = await sendAuthorizedBackendRequest(auth, {
    endpoint: "ai-report-current", params: { caseId }, expectedStatus: 200,
    validate: current, signal,
  });
  if (result.data.caseId !== caseId ||
      (result.data.currentReport !== null && result.data.currentReport.caseId !== caseId) ||
      (result.data.latestRequest !== null && result.data.latestRequest.caseId !== caseId)) {
    throw new InvalidResponseError();
  }
  return result.data;
}

export async function createAiReport(auth: CredentialAuthClient, caseId: string,
  detectionResultVersion: number, idempotencyKey: string,
  signal?: AbortSignal): Promise<AiReportRequestStatus> {
  if (!version(detectionResultVersion) || !isCanonicalUuidV4(caseId)) {
    throw new RequestNotAllowedError();
  }
  const result = await sendAuthorizedBackendRequest(auth, {
    endpoint: "ai-report-create", params: { caseId },
    body: { detectionResultVersion, regenerationReason: null }, idempotencyKey,
    expectedStatus: 202, validate: status, signal,
  });
  if (result.data.detectionResultVersion !== detectionResultVersion || result.data.caseId !== caseId) {
    throw new InvalidResponseError();
  }
  return result.data;
}
