import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { isApprovedQueryValue } from "./backendEndpoints";
import { RequestNotAllowedError } from "./errors";
import { isJsonObject, isObjectWithExactKeys, isSafeLong, isTraceIdString,
  isUtcInstantString, resolveTraceId } from "./responseValidation";

const LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const SCENARIOS = ["SCN001", "SCN002", "SCN003", "SCN004", "SCN005", "SCN006", "SCN007", "SCN008"] as const;
const STATUSES = ["OPEN", "IN_REVIEW", "ADDITIONAL_INFORMATION_REQUIRED", "CLOSED"] as const;

export interface DashboardRange { readonly from: string; readonly to: string }
export interface TransactionDay {
  readonly date: string; readonly total: number; readonly unclassified: number;
  readonly classified: number; readonly riskLevels: Readonly<Record<typeof LEVELS[number], number>>;
  readonly scenarios: Readonly<Record<typeof SCENARIOS[number], number>>;
}
export interface CaseDay { readonly date: string; readonly newCases: number }
export interface TransactionDashboard {
  readonly from: string; readonly to: string; readonly timeZone: "Asia/Seoul";
  readonly asOf: string; readonly days: readonly TransactionDay[]; readonly traceId: string;
}
export interface CaseDashboard {
  readonly from: string; readonly to: string; readonly timeZone: "Asia/Seoul";
  readonly asOf: string; readonly days: readonly CaseDay[];
  readonly currentStatuses: Readonly<Record<typeof STATUSES[number], number>>;
  readonly traceId: string;
}

function count(value: unknown): value is number {
  return isSafeLong(value) && value >= 0;
}

function date(value: unknown): value is string {
  return typeof value === "string" && isApprovedQueryValue({ kind: "kst-date" }, value);
}

function counts(value: unknown, keys: readonly string[]): boolean {
  return isObjectWithExactKeys(value, keys) && keys.every(key => count(value[key]));
}

function shared(value: unknown): value is Record<string, unknown> {
  return isJsonObject(value) && date(value.from) && date(value.to) &&
    value.timeZone === "Asia/Seoul" && isUtcInstantString(value.asOf) &&
    isTraceIdString(value.traceId) && Array.isArray(value.days);
}

function validDates(days: readonly { readonly date: string }[], from: string, to: string): boolean {
  const expected = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000;
  return Number.isInteger(expected) && expected >= 1 && expected <= 31 && days.length === expected &&
    days.every((day, index) => day.date === new Date(Date.parse(`${from}T00:00:00Z`) + index * 86400000)
      .toISOString().slice(0, 10));
}

export function isTransactionDashboard(value: unknown): value is TransactionDashboard {
  if (!isObjectWithExactKeys(value, ["from", "to", "timeZone", "asOf", "days", "traceId"]) || !shared(value)) return false;
  const days = value.days as unknown[];
  return days.every(day => isObjectWithExactKeys(day,
    ["date", "total", "unclassified", "classified", "riskLevels", "scenarios"]) &&
    date(day.date) && count(day.total) && count(day.unclassified) && count(day.classified) &&
    counts(day.riskLevels, LEVELS) && counts(day.scenarios, SCENARIOS) &&
    day.total === (day.unclassified as number) + (day.classified as number) &&
    LEVELS.reduce((sum, level) => sum + (day.riskLevels as Record<string, number>)[level], 0) === day.classified &&
    SCENARIOS.every(s => (day.scenarios as Record<string, number>)[s] <= (day.classified as number))) &&
    validDates(days as TransactionDay[], value.from as string, value.to as string);
}

export function isCaseDashboard(value: unknown): value is CaseDashboard {
  if (!isObjectWithExactKeys(value,
    ["from", "to", "timeZone", "asOf", "days", "currentStatuses", "traceId"]) || !shared(value) ||
      !counts(value.currentStatuses, STATUSES)) return false;
  const days = value.days as unknown[];
  return days.every(day => isObjectWithExactKeys(day, ["date", "newCases"]) &&
    date(day.date) && count(day.newCases)) &&
    validDates(days as CaseDay[], value.from as string, value.to as string) &&
    days.reduce<number>((sum, day) => sum + (day as CaseDay).newCases, 0) ===
      STATUSES.reduce((sum, status) => sum + (value.currentStatuses as Record<string, number>)[status], 0);
}

function query(range?: DashboardRange): Readonly<Record<string, string>> | undefined {
  if (range === undefined) return undefined;
  if (!date(range.from) || !date(range.to)) throw new RequestNotAllowedError();
  return { from: range.from, to: range.to };
}

export async function fetchTransactionDashboard(auth: CredentialAuthClient, range?: DashboardRange,
  signal?: AbortSignal): Promise<TransactionDashboard> {
  const result = await sendAuthorizedBackendRequest(auth, {
    endpoint: "fds-dashboard-transactions", query: query(range), expectedStatus: 200,
    validate: isTransactionDashboard, signal,
  });
  resolveTraceId(result.traceId, result.data.traceId);
  return result.data;
}

export async function fetchCaseDashboard(auth: CredentialAuthClient, range?: DashboardRange,
  signal?: AbortSignal): Promise<CaseDashboard> {
  const result = await sendAuthorizedBackendRequest(auth, {
    endpoint: "fds-dashboard-cases", query: query(range), expectedStatus: 200,
    validate: isCaseDashboard, signal,
  });
  resolveTraceId(result.traceId, result.data.traceId);
  return result.data;
}
