import type { CredentialAuthClient } from "../auth/authClient";
import { readExactRequestFields, sendAuthorizedBackendRequest } from "./authorizedClient";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import { InvalidResponseError } from "./errors";

export interface DlqCoordinate { topicId: string; partition: number; offset: number }
export interface DlqDiagnostic extends DlqCoordinate {
  failureCategory: "INVALID_EVENT" | "PRE_CLAIM_TRANSIENT" | "UNKNOWN";
  sourceVerified: boolean; sourceRecovered: boolean;
  eventId: string | null; executionId: string | null;
  executionStatus: string | null; reportExists: boolean; attemptExists: boolean;
  action: "QUARANTINE" | "REPLAY" | null;
  dispatchStatus: "PENDING" | "CLAIMED" | "ACKED" | "BLOCKED" | "SKIPPED" | null;
  startSource: "KAFKA" | "POLLING" | null;
  ackPartition: number | null; ackOffset: number | null;
  replayAllowed: boolean; rejectionReason: string | null; traceId: string;
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function uuidOrNull(value: unknown): boolean {
  return value === null || (typeof value === "string" && isCanonicalUuidV4(value));
}
function diagnostic(value: unknown): value is DlqDiagnostic {
  if (!object(value)) return false;
  const keys = ["topicId", "partition", "offset", "failureCategory", "sourceVerified",
    "sourceRecovered", "eventId", "executionId", "executionStatus", "reportExists",
    "attemptExists", "action", "dispatchStatus", "startSource", "ackPartition",
    "ackOffset", "replayAllowed", "rejectionReason", "traceId"];
  if (Object.keys(value).length !== keys.length || !keys.every((key) => Object.hasOwn(value, key))) return false;
  return typeof value.topicId === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.topicId) &&
    Number.isSafeInteger(value.partition) && Number(value.partition) >= 0 &&
    Number.isSafeInteger(value.offset) && Number(value.offset) >= 0 &&
    ["INVALID_EVENT", "PRE_CLAIM_TRANSIENT", "UNKNOWN"].includes(String(value.failureCategory)) &&
    typeof value.sourceVerified === "boolean" && typeof value.sourceRecovered === "boolean" &&
    uuidOrNull(value.eventId) && uuidOrNull(value.executionId) &&
    (value.executionStatus === null || ["PENDING", "GENERATING", "COMPLETED", "FALLBACK_COMPLETED", "FAILED"].includes(String(value.executionStatus))) &&
    typeof value.reportExists === "boolean" && typeof value.attemptExists === "boolean" &&
    (value.action === null || ["QUARANTINE", "REPLAY"].includes(String(value.action))) &&
    (value.dispatchStatus === null || ["PENDING", "CLAIMED", "ACKED", "BLOCKED", "SKIPPED"].includes(String(value.dispatchStatus))) &&
    (value.startSource === null || ["KAFKA", "POLLING"].includes(String(value.startSource))) &&
    (value.ackPartition === null || Number.isSafeInteger(value.ackPartition)) &&
    (value.ackOffset === null || Number.isSafeInteger(value.ackOffset)) &&
    typeof value.replayAllowed === "boolean" &&
    (value.rejectionReason === null || typeof value.rejectionReason === "string") &&
    typeof value.traceId === "string";
}
function params(value: DlqCoordinate) {
  return { topicId: value.topicId, partition: String(value.partition), offset: String(value.offset) };
}
function sameCoordinate(expected: DlqCoordinate, actual: DlqDiagnostic) {
  if (actual.topicId !== expected.topicId || actual.partition !== expected.partition ||
      actual.offset !== expected.offset) throw new InvalidResponseError();
}
export async function fetchDlq(auth: CredentialAuthClient, coordinate: DlqCoordinate,
  signal?: AbortSignal): Promise<DlqDiagnostic> {
  const response = await sendAuthorizedBackendRequest(auth, { endpoint: "ai-dlq-diagnostic",
    params: params(coordinate), expectedStatus: 200, validate: diagnostic, signal });
  sameCoordinate(coordinate, response.data);
  return response.data;
}
export async function decideDlq(auth: CredentialAuthClient, prior: DlqDiagnostic,
  action: "quarantine" | "replay"): Promise<DlqDiagnostic> {
  const response = await sendAuthorizedBackendRequest(auth, {
    endpoint: action === "replay" ? "ai-dlq-replay" : "ai-dlq-quarantine",
    params: params(prior), body: readExactRequestFields({ observedCategory: prior.failureCategory },
      ["observedCategory"]), expectedStatus: 202, validate: diagnostic });
  sameCoordinate(prior, response.data);
  if (response.data.action !== (action === "replay" ? "REPLAY" : "QUARANTINE"))
    throw new InvalidResponseError();
  return response.data;
}
