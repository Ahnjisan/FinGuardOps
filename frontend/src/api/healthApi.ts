import { getEnv } from "../config/env";
import { InvalidResponseError } from "./errors";
import { httpGet } from "./httpClient";
import type { HealthResponse, HealthResult } from "./types";

const HEALTH_REQUEST_TIMEOUT_MS = 5000;
const TRACE_ID_HEADER = "X-Trace-Id";

/**
 * Official X-Trace-Id contract: full match only, length 8-64, first char
 * alphanumeric, remaining chars alphanumeric plus . _ : -
 */
const TRACE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$/;

function isSafeTraceId(value: string): boolean {
  return TRACE_ID_PATTERN.test(value);
}

function extractSafeTraceId(headers: Headers): string | undefined {
  const rawTraceId = headers.get(TRACE_ID_HEADER);
  if (rawTraceId !== null && isSafeTraceId(rawTraceId)) {
    return rawTraceId;
  }
  return undefined;
}

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const entries = Object.keys(value);
  if (entries.length !== 2) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return record.status === "UP" && record.service === "backend";
}

export async function fetchHealth(signal?: AbortSignal): Promise<HealthResult> {
  const { apiBaseUrl } = getEnv();
  const url = `${apiBaseUrl}/api/health`;

  const response = await httpGet(url, { timeoutMs: HEALTH_REQUEST_TIMEOUT_MS, signal });

  if (!isHealthResponse(response.body)) {
    throw new InvalidResponseError();
  }

  return {
    data: response.body,
    traceId: extractSafeTraceId(response.headers),
  };
}
