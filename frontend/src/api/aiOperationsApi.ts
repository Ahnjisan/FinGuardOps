import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import { InvalidResponseError } from "./errors";
import { isUtcInstantString } from "./responseValidation";

export interface AiAttempt {
  attemptNumber: number; provider: string; model: string | null; outcome: string;
  inputTokens: number | null; outputTokens: number | null; totalTokens: number | null;
  estimatedCost: string | null; costCurrency: string | null; latencyMs: number;
}
export interface AiUsageItem {
  aiRequestId: string; executionId: string | null; executionShared: boolean;
  initiatingAiRequestId: string | null; reportId: string | null; caseId: string;
  detectionResultVersion: number; reportStatus: string; reportSource: string | null;
  sourceAiRequestId: string | null; lastProvider: string | null; lastModel: string | null;
  promptVersion: string; modelVersion: string; inputTokens: number | null;
  outputTokens: number | null; totalTokens: number | null; estimatedCost: string | null;
  costCurrency: string | null; costBreakdown: [] | null; latencyMs: null;
  cacheHit: boolean; fallbackUsed: boolean; requestedAt: string; completedAt: null;
  failureCode: string | null; traceId: string;
}
export interface AiUsageDetail extends AiUsageItem {
  usageFinalized: boolean; requestedByRef: string; attempts: AiAttempt[]; queryTraceId: string;
}
export interface AiUsageList {
  content: AiUsageItem[];
  page: { number: number; size: number; totalElements: number; totalPages: number;
    first: boolean; last: boolean };
  traceId: string;
}
export interface AiUsageSummary {
  from: string; to: string; requestCount: number; executionCount: number;
  providerCallCount: number; successCount: number; failureCount: number;
  inProgressCount: number; fallbackCount: number; cacheHitCount: number;
  inputTokens: number | null; outputTokens: number | null; totalTokens: number | null;
  estimatedCost: null; costCurrency: null; costBreakdown: [] | null;
  averageLatencyMs: null; traceId: string;
}
export interface AiUsageFilters {
  from: string; to: string; provider?: string; model?: string; reportStatus?: string;
  reportSource?: string; cacheHit?: string; fallbackUsed?: string;
}
export interface AiUsageListQuery extends AiUsageFilters {
  page?: string; size?: string; sort?: string;
}

function obj(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function exact(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key));
}
function uuidOrNull(value: unknown): boolean {
  return value === null || (typeof value === "string" && isCanonicalUuidV4(value));
}
function textOrNull(value: unknown): boolean {
  return value === null || typeof value === "string";
}
function countOrNull(value: unknown): boolean {
  return value === null || (typeof value === "number" && Number.isSafeInteger(value) && value >= 0);
}
function count(value: unknown): boolean { return countOrNull(value) && value !== null; }
function cost(value: unknown, currency: unknown, breakdown: unknown, calls: boolean): boolean {
  return value === null && currency === null && (breakdown === null ||
    (Array.isArray(breakdown) && breakdown.length === 0 && !calls));
}
const ITEM_KEYS = ["aiRequestId", "executionId", "executionShared", "initiatingAiRequestId",
  "reportId", "caseId", "detectionResultVersion", "reportStatus", "reportSource",
  "sourceAiRequestId", "lastProvider", "lastModel", "promptVersion", "modelVersion",
  "inputTokens", "outputTokens", "totalTokens", "estimatedCost", "costCurrency",
  "costBreakdown", "latencyMs", "cacheHit", "fallbackUsed", "requestedAt",
  "completedAt", "failureCode", "traceId"];
const STATUSES = ["PENDING", "GENERATING", "COMPLETED", "FALLBACK_COMPLETED", "FAILED"];
function item(value: unknown): value is AiUsageItem {
  if (!obj(value) || !exact(value, ITEM_KEYS)) return false;
  return typeof value.aiRequestId === "string" && isCanonicalUuidV4(value.aiRequestId) && uuidOrNull(value.executionId) &&
    typeof value.executionShared === "boolean" && uuidOrNull(value.initiatingAiRequestId) &&
    uuidOrNull(value.reportId) && typeof value.caseId === "string" && isCanonicalUuidV4(value.caseId) &&
    count(value.detectionResultVersion) && value.detectionResultVersion !== 0 &&
    STATUSES.includes(String(value.reportStatus)) &&
    (value.reportSource === null || ["LLM", "TEMPLATE_FALLBACK"].includes(String(value.reportSource))) &&
    uuidOrNull(value.sourceAiRequestId) && textOrNull(value.lastProvider) &&
    textOrNull(value.lastModel) && typeof value.promptVersion === "string" &&
    typeof value.modelVersion === "string" && countOrNull(value.inputTokens) &&
    countOrNull(value.outputTokens) && countOrNull(value.totalTokens) &&
    cost(value.estimatedCost, value.costCurrency, value.costBreakdown, false) &&
    value.latencyMs === null && typeof value.cacheHit === "boolean" &&
    typeof value.fallbackUsed === "boolean" && isUtcInstantString(value.requestedAt) &&
    value.completedAt === null && textOrNull(value.failureCode) && typeof value.traceId === "string";
}
function attempt(value: unknown): value is AiAttempt {
  if (!obj(value) || !exact(value, ["attemptNumber", "provider", "model", "outcome",
    "inputTokens", "outputTokens", "totalTokens", "estimatedCost", "costCurrency", "latencyMs"])) return false;
  return count(value.attemptNumber) && Number(value.attemptNumber) > 0 && typeof value.provider === "string" &&
    textOrNull(value.model) && typeof value.outcome === "string" &&
    countOrNull(value.inputTokens) && countOrNull(value.outputTokens) &&
    countOrNull(value.totalTokens) && value.estimatedCost === null &&
    value.costCurrency === null && count(value.latencyMs);
}
function detail(value: unknown): value is AiUsageDetail {
  if (!obj(value) || !exact(value, [...ITEM_KEYS, "usageFinalized", "requestedByRef", "attempts", "queryTraceId"])) return false;
  const core = Object.fromEntries(ITEM_KEYS.map((key) => [key, value[key]]));
  return item(core) && typeof value.usageFinalized === "boolean" &&
    typeof value.requestedByRef === "string" && Array.isArray(value.attempts) &&
    value.attempts.every(attempt) && typeof value.queryTraceId === "string" &&
    (value.executionId !== null || value.attempts.length === 0) &&
    (value.attempts.length === 0 ? Array.isArray(value.costBreakdown) &&
      value.costBreakdown.length === 0 : value.costBreakdown === null);
}
function list(value: unknown): value is AiUsageList {
  if (!obj(value) || !exact(value, ["content", "page", "traceId"]) ||
      !Array.isArray(value.content) || !value.content.every(item) || !obj(value.page)) return false;
  const p = value.page;
  return exact(p, ["number", "size", "totalElements", "totalPages", "first", "last"]) &&
    count(p.number) && count(p.size) && count(p.totalElements) && count(p.totalPages) &&
    typeof p.first === "boolean" && typeof p.last === "boolean" && typeof value.traceId === "string";
}
function summary(value: unknown): value is AiUsageSummary {
  if (!obj(value) || !exact(value, ["from", "to", "requestCount", "executionCount",
    "providerCallCount", "successCount", "failureCount", "inProgressCount", "fallbackCount",
    "cacheHitCount", "inputTokens", "outputTokens", "totalTokens", "estimatedCost",
    "costCurrency", "costBreakdown", "averageLatencyMs", "traceId"])) return false;
  return isUtcInstantString(value.from) && isUtcInstantString(value.to) &&
    ["requestCount", "executionCount", "providerCallCount", "successCount", "failureCount",
      "inProgressCount", "fallbackCount", "cacheHitCount"].every((key) => count(value[key])) &&
    countOrNull(value.inputTokens) && countOrNull(value.outputTokens) &&
    countOrNull(value.totalTokens) && cost(value.estimatedCost, value.costCurrency,
      value.costBreakdown, value.providerCallCount !== 0) &&
    value.averageLatencyMs === null && typeof value.traceId === "string" &&
    Number(value.successCount) + Number(value.failureCount) + Number(value.inProgressCount) === value.requestCount;
}
export async function fetchAiUsageList(auth: CredentialAuthClient, query: AiUsageListQuery,
  signal?: AbortSignal): Promise<AiUsageList> {
  const result = await sendAuthorizedBackendRequest(auth, { endpoint: "ai-usage-list",
    query: { ...query }, expectedStatus: 200, validate: list, signal });
  return result.data;
}
export async function fetchAiUsageSummary(auth: CredentialAuthClient, query: AiUsageFilters,
  signal?: AbortSignal): Promise<AiUsageSummary> {
  const result = await sendAuthorizedBackendRequest(auth, { endpoint: "ai-usage-summary",
    query: { ...query }, expectedStatus: 200, validate: summary, signal });
  if (result.data.from !== query.from || result.data.to !== query.to) throw new InvalidResponseError();
  return result.data;
}
export async function fetchAiRequestDetail(auth: CredentialAuthClient, aiRequestId: string,
  signal?: AbortSignal): Promise<AiUsageDetail> {
  const result = await sendAuthorizedBackendRequest(auth, { endpoint: "ai-operations-detail",
    params: { aiRequestId }, expectedStatus: 200, validate: detail, signal });
  if (result.data.aiRequestId !== aiRequestId) throw new InvalidResponseError();
  return result.data;
}
